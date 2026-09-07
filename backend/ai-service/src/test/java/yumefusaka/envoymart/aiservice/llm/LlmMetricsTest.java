package yumefusaka.envoymart.aiservice.llm;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型调用的耗时与 token 指标。
 * <p>
 * 这两个指标是成本可观测的唯一依据——日志回答"这一次发生了什么"，指标回答"最近一周贵在哪"。
 * 用桩 ChatModel 固定住埋点行为，避免以后重构时把指标悄悄丢掉。
 */
class LlmMetricsTest {

    private static final String MODEL = "stub-model";

    /** 只实现 call(Prompt)，其余方法走接口默认实现 */
    private final ChatModel stubModel = prompt -> new ChatResponse(
            List.of(new Generation(new AssistantMessage("你好"))),
            ChatResponseMetadata.builder()
                    .usage(new DefaultUsage(120, 30))
                    .build());

    @Test
    void 一次同步调用应记录耗时与两个方向的token用量() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        provider(registry).chat(List.of(userMessage()), config());

        assertThat(registry.get("agent.llm.latency").tag("model", MODEL).tag("mode", "sync")
                .timer().count()).isEqualTo(1);

        assertThat(registry.get("agent.llm.tokens").tag("model", MODEL).tag("type", "prompt")
                .counter().count()).isEqualTo(120);
        assertThat(registry.get("agent.llm.tokens").tag("model", MODEL).tag("type", "completion")
                .counter().count()).isEqualTo(30);
    }

    @Test
    void 同步指标不应把流式调用算进去() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        try {
            provider(registry).chatStream(List.of(userMessage()), config(), chunk -> {
            });
        } catch (Exception ignored) {
            // 桩模型没有流式实现，这里只关心标签隔离
        }

        assertThat(registry.find("agent.llm.latency").tag("mode", "sync").timer()).isNull();
    }

    private SpringAiLLMProvider provider(SimpleMeterRegistry registry) {
        return new SpringAiLLMProvider(stubModel, new ToolRegistry(), config(), registry);
    }

    private LLMConfig config() {
        return LLMConfig.builder().model(MODEL).temperature(0.0).maxTokens(100).build();
    }

    private ChatMessage userMessage() {
        return ChatMessage.builder().role(ChatMessage.Role.USER).content("你好").build();
    }
}
