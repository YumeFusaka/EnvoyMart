package yumefusaka.envoymart.aiservice.controller;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import yumefusaka.envoymart.aiservice.model.ChatRequest;
import yumefusaka.envoymart.aiservice.model.ChatResponse;
import yumefusaka.envoymart.aiservice.service.AiAssistantService;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AiController {

    /** 流式响应跑在虚拟线程上，不占用容器请求线程 */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final AiAssistantService aiAssistantService;

    public AiController(AiAssistantService aiAssistantService) {
        this.aiAssistantService = aiAssistantService;
    }

    @PostMapping("/chat")
    public Result<ChatResponse> chat(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                     @Valid @RequestBody ChatRequest request) {
        return Result.success(aiAssistantService.chat(userId, request));
    }

    /**
     * SSE 流式对话。
     * <p>
     * 事件类型：`delta` 增量文本、`done` 完整结果（含知识命中与工具轨迹）、`error` 异常。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                 @Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> log.warn("[SSE] emitter error: {}", e.getMessage()));

        streamExecutor.submit(() -> {
            try {
                ChatResponse response = aiAssistantService.chatStream(userId, request, chunk -> send(emitter, "delta", chunk));
                emitter.send(SseEmitter.event().name("done").data(response, MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception e) {
                log.error("[SSE] chat stream failed", e);
                send(emitter, "error", "智能助手暂时不可用，请稍后再试");
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            // 客户端提前断开属正常情况，不需要向上抛
            log.debug("[SSE] client disconnected: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        streamExecutor.shutdown();
    }
}
