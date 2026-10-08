package yumefusaka.envoymart.aiservice.service;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.core.Agent;
import yumefusaka.envoymart.agent.core.AgentCancelledException;
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.aiservice.llm.ModelPricing;
import yumefusaka.envoymart.aiservice.memory.ChatHistoryStore;
import yumefusaka.envoymart.aiservice.memory.ChatIdempotencyStore;
import yumefusaka.envoymart.aiservice.model.ChatRequest;
import yumefusaka.envoymart.aiservice.model.ChatResponse;
import yumefusaka.envoymart.aiservice.service.impl.AiAssistantServiceImpl;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务层的两条收尾语义：<b>「重新生成」落历史的形态</b>，与<b>取消时的部分答复落历史</b>。
 * <p>
 * 两条都遵循同一个原则：<b>历史必须与屏幕一致</b>。
 * <ul>
 *   <li>重新生成在界面上是原地替换，历史里就不该每次多出一对重复问答；</li>
 *   <li>用户按停止后回看会话，看到的必须是「他自己看到过的那半句」——
 *       要么是一段从未见过的完整答案（比少记更坏的谎），要么是一片空白
 *       （他明明读了一半）。</li>
 * </ul>
 */
class AiAssistantServiceTest {

    private ChatRequest request(boolean regenerate) {
        ChatRequest request = new ChatRequest();
        request.setSessionId("s1");
        request.setMessage("原问题");
        request.setRegenerate(regenerate);
        return request;
    }

    private AiAssistantServiceImpl service(Agent agent, ChatHistoryStore history) {
        ChatIdempotencyStore idempotency = mock(ChatIdempotencyStore.class);
        // 这几条用例钉的是「历史怎么记」，不是幂等；让占位一律成功，避免 mock 的默认
        // 返回值（false）把流程引到「相同请求正在处理中」那条分支上
        when(idempotency.tryAcquire(anyString(), any())).thenReturn(true);
        return new AiAssistantServiceImpl(agent, mock(ModelPricing.class), history, idempotency,
                mock(CommerceCardAssembler.class));
    }

    @Test
    void 重新生成就地改写历史而不是追加一轮() {
        Agent agent = mock(Agent.class);
        when(agent.chat(anyString(), anyString(), anyString(), any()))
                .thenReturn(Agent.AgentResponse.builder().reply("新答复").build());
        ChatHistoryStore history = mock(ChatHistoryStore.class);

        service(agent, history).chat("u1", request(true));

        verify(history).recordAnswer(eq("u1"), eq("s1"), eq("新答复"), any());
        verify(history, never()).recordTurn(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void 普通一轮照常记一对问答() {
        Agent agent = mock(Agent.class);
        when(agent.chat(anyString(), anyString(), anyString(), any()))
                .thenReturn(Agent.AgentResponse.builder().reply("新答复").build());
        ChatHistoryStore history = mock(ChatHistoryStore.class);

        service(agent, history).chat("u1", request(false));

        verify(history).recordTurn(eq("u1"), eq("s1"), eq("原问题"), eq("新答复"), any());
        verify(history, never()).recordAnswer(anyString(), anyString(), any(), any());
    }

    @Test
    void 取消时把已经推出去的部分答复落历史() {
        Agent agent = mock(Agent.class);
        when(agent.chatStream(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> onChunk = invocation.getArgument(4);
                    onChunk.accept("前半句");
                    onChunk.accept("，后半句没生成完");
                    throw new AgentCancelledException("本轮对话已被取消");
                });
        ChatHistoryStore history = mock(ChatHistoryStore.class);

        assertThatThrownBy(() -> service(agent, history)
                .chatStream("u1", request(false), chunk -> {
                }, ToolProgressListener.NOOP))
                .isInstanceOf(AgentCancelledException.class);

        verify(history).recordTurn(eq("u1"), eq("s1"), eq("原问题"),
                eq("前半句，后半句没生成完"), any());
    }

    @Test
    void 取消发生在重新生成中时同样原改写历史() {
        Agent agent = mock(Agent.class);
        when(agent.chatStream(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> onChunk = invocation.getArgument(4);
                    onChunk.accept("重写了一半");
                    throw new AgentCancelledException("本轮对话已被取消");
                });
        ChatHistoryStore history = mock(ChatHistoryStore.class);

        assertThatThrownBy(() -> service(agent, history)
                .chatStream("u1", request(true), chunk -> {
                }, ToolProgressListener.NOOP))
                .isInstanceOf(AgentCancelledException.class);

        // 重新生成的取消收尾也走改写：历史里是「重写了一半」，而不是又多出第三条
        verify(history).recordAnswer(eq("u1"), eq("s1"), eq("重写了一半"), any());
        verify(history, never()).recordTurn(anyString(), anyString(), anyString(), any(), any());
    }

    /**
     * 取消<b>之后</b>到达的模型分片不计入「已交付」：屏幕停在断开的那一刻，
     * 历史必须停在同一个字。
     * <p>
     * 断开不会让模型立刻停——还在途的那一轮会继续把分片推过来，而控制器已经
     * 不再往那条连接上写。把它们算进已交付，回看会话时会发现回答比屏幕上多出一截，
     * 而那一截用户从没见过。
     */
    @Test
    void 取消之后到达的模型分片不计入已交付文本() {
        Agent agent = mock(Agent.class);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ToolProgressListener listener = new ToolProgressListener() {
            @Override
            public void onStart(String tool) {
            }

            @Override
            public void onFinish(String tool, boolean success, boolean noData, long latencyMs) {
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };
        when(agent.chatStream(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> onChunk = invocation.getArgument(4);
                    onChunk.accept("用户看到的前半句");
                    cancelled.set(true); // 连接在这一刻断开
                    onChunk.accept("推给空气的后半句");
                    throw new AgentCancelledException("本轮对话已被取消");
                });
        ChatHistoryStore history = mock(ChatHistoryStore.class);

        assertThatThrownBy(() -> service(agent, history)
                .chatStream("u1", request(false), chunk -> {
                }, listener))
                .isInstanceOf(AgentCancelledException.class);

        verify(history).recordTurn(eq("u1"), eq("s1"), eq("原问题"),
                eq("用户看到的前半句"), any());
    }

    /**
     * 同一请求号重复到达时，不再跑一遍 Agent —— 这是幂等真正要挡住的事。
     * <p>
     * 钉子钉在「模型调用次数」上而不是返回值上：返回值可以靠缓存伪造，
     * 而重复执行一轮 Agent 的代价（重复计费、工具重复执行）只有调用次数能证明。
     */
    @Test
    void 相同请求号重复到达时不重复执行() {
        Agent agent = mock(Agent.class);
        ChatHistoryStore history = mock(ChatHistoryStore.class);
        ChatIdempotencyStore idempotency = mock(ChatIdempotencyStore.class);
        when(idempotency.tryAcquire(anyString(), any())).thenReturn(false);
        ChatResponse cached = ChatResponse.builder().reply("上一次的回答").build();
        when(idempotency.previous(anyString(), any(), eq(ChatResponse.class)))
                .thenReturn(java.util.Optional.of(cached));
        AiAssistantServiceImpl service =
                new AiAssistantServiceImpl(agent, mock(ModelPricing.class), history, idempotency,
                        mock(CommerceCardAssembler.class));

        // 幂等的判据是当前请求号；单测没有请求上下文，得显式摆一个
        org.slf4j.MDC.put(yumefusaka.envoymart.common.web.RequestId.MDC_KEY, "req-1");
        ChatResponse response;
        try {
            response = service.chat("u1", request(false));
        } finally {
            org.slf4j.MDC.remove(yumefusaka.envoymart.common.web.RequestId.MDC_KEY);
        }

        assertThat(response.getReply()).isEqualTo("上一次的回答");
        verify(agent, never()).chat(anyString(), anyString(), anyString(), any());
        verify(history, never()).recordTurn(anyString(), anyString(), anyString(), any(), any());
    }
}
