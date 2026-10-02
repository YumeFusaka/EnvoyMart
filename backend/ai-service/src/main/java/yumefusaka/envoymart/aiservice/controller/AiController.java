package yumefusaka.envoymart.aiservice.controller;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import yumefusaka.envoymart.agent.core.AgentCancelledException;
import yumefusaka.envoymart.agent.core.AgentGraph;
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.aiservice.model.ChatRequest;
import yumefusaka.envoymart.aiservice.model.ChatResponse;
import yumefusaka.envoymart.aiservice.service.AiAssistantService;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.common.web.RequestId;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AiController {

    /** 流式响应跑在虚拟线程上，不占用容器请求线程 */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final AiAssistantService aiAssistantService;
    private final AgentGraph agentGraph;

    public AiController(AiAssistantService aiAssistantService, AgentGraph agentGraph) {
        this.aiAssistantService = aiAssistantService;
        this.agentGraph = agentGraph;
    }

    /**
     * 导出 Agent 执行图（mermaid）。
     * <p>
     * 图是显式注册的，所以能直接画出来——调试时能一眼看清"下一步会去哪"，
     * 也方便放进设计文档。
     */
    @GetMapping("/graph")
    public Result<String> graph() {
        return Result.success(agentGraph.toMermaid());
    }

    @PostMapping("/chat")
    public Result<ChatResponse> chat(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                     @Valid @RequestBody ChatRequest request) {
        return Result.success(aiAssistantService.chat(userId, request));
    }

    /**
     * SSE 流式对话。
     * <p>
     * 事件类型：`delta` 增量文本、`tool` 工具执行进度（start/finish）、
     * `done` 完整结果（含知识命中与工具轨迹）、`error` 异常。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                 @Valid @RequestBody ChatRequest request) {
        // 600s：SSE 的总寿命必须罩得住内部各段超时的最坏组合（多次模型往返 + 工具批次），
        // 180s 会在一次正常的长回答中途剪断连接。取消语义已就位，超时不再是唯一的失联手段
        SseEmitter emitter = new SseEmitter(600_000L);

        // 三类「对端已经离开」都立起取消旗，执行链在每一个「即将开始新工作」的位置查它：
        // 断开（onError）、超时（onTimeout）、正常收尾（onCompletion，此时工作已结束，
        // 置位无害）。早先只登记了日志，于是用户关掉页面后服务端继续跑完整张计划——
        // 模型继续计费、工具继续执行，而这一切的接收方早已不存在
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onTimeout(() -> {
            cancelled.set(true);
            emitter.complete();
        });
        emitter.onError(e -> {
            cancelled.set(true);
            log.warn("[SSE] emitter error: {}", e.getMessage());
        });
        emitter.onCompletion(() -> cancelled.set(true));

        // 流式这段跑在另一个线程上，日志上下文要显式带过去（见 RequestId#inherit）：
        // 不带的话，整轮对话里最有价值的那些日志（工具调用、模型往返、检索命中）
        // 全部没有请求标识，恰好是最需要串联的一段断了线
        streamExecutor.submit(RequestId.inherit(() -> {
            try {
                ChatResponse response = aiAssistantService.chatStream(userId, request,
                        chunk -> {
                            // 取消后不再往一条已经死掉的连接上写：不是正确性问题
                            // （send 会吞 IOException），是别让断开后的每一片 token 都付一次发送尝试
                            if (!cancelled.get()) {
                                send(emitter, "delta", chunk);
                            }
                        },
                        toolProgress(emitter, cancelled));
                sendFinal(emitter, response);
                emitter.complete();
            } catch (AgentCancelledException e) {
                // 取消的收尾：不发 error 事件（对端多半已经不在了），更不打 ERROR 堆栈——
                // 用户按的「停止生成」不是系统故障。部分答复已由服务层落过历史
                log.info("[SSE] 本轮已取消 userId={} sessionId={}", userId, request.getSessionId());
                emitter.complete();
            } catch (Exception e) {
                log.error("[SSE] chat stream failed", e);
                send(emitter, "error", "智能助手暂时不可用，请稍后再试");
                emitter.complete();
            }
        }));
        return emitter;
    }

    /**
     * 工具执行进度 → SSE `tool` 事件。
     * <p>
     * 这是编排阶段的唯一可见通道：出首个正文块之前可能要先跑几个工具，
     * 没有它界面在首字到达前是一片空白。只发真正执行了的调用——
     * 被循环护栏或高危闸口拦下的调用从未发生，发出去就是让用户看一个不存在的「正在执行」。
     * <p>
     * 监听器是请求级的（绑定这一条 SSE 连接）：工具执行可能发生在任意线程，
     * 全局单例会把并发请求的进度串到别人的连接上。
     * <p>
     * 取消信号搭同一个对象下发（{@link ToolProgressListener#cancelled()}）：它已经是
     * 执行链上唯一贯穿全程的请求级通道，再开一条参数通道只会多一个「某处忘了传」的机会。
     */
    private ToolProgressListener toolProgress(SseEmitter emitter, AtomicBoolean cancelled) {
        return new ToolProgressListener() {
            @Override
            public void onStart(String tool) {
                sendJson(emitter, "tool", Map.of("phase", "start", "tool", tool));
            }

            @Override
            public void onFinish(String tool, boolean success, boolean noData, long latencyMs) {
                sendJson(emitter, "tool", Map.of(
                        "phase", "finish",
                        "tool", tool,
                        "success", success,
                        "noData", noData,
                        "latencyMs", latencyMs));
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            // 客户端提前断开属正常情况，不需要向上抛
            log.debug("[SSE] client disconnected: {}", e.getMessage());
        }
    }

    /** 结构化载荷（Map）必须显式声明 JSON：SseEmitter 不带媒体类型时按 text/plain 解析，Map 写不出去 */
    private void sendJson(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("[SSE] client disconnected: {}", e.getMessage());
        }
    }

    /**
     * 终态事件的发送。
     * <p>
     * 客户端在这一刻断开（关页面、切会话、点停止）是常态而非故障，所以不能让它冒泡成
     * ERROR + 堆栈——但也不能像 {@code delta} 那样只记 debug：增量的丢失是「少看了半句」，
     * 终态的丢失意味着**引用、工具轨迹、用量全部没到用户手上**，整轮白跑，必须留痕。
     */
    private void sendFinal(SseEmitter emitter, ChatResponse response) {
        try {
            emitter.send(SseEmitter.event().name("done").data(response, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.warn("[SSE] 终态未送达，本轮结果用户不可见：{}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        streamExecutor.shutdown();
    }
}
