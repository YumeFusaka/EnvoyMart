package yumefusaka.envoymart.aiservice.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型调用的耗时与 token 指标。
 * <p>
 * 这两个指标是成本可观测的唯一依据——日志回答"这一次发生了什么"，指标回答"最近一周贵在哪"。
 * 用桩模型固定住埋点行为，避免以后重构时把指标悄悄丢掉。
 */
class LlmMetricsTest {

    private static final String MODEL = "stub-model";

    /** 只实现 chat(ChatRequest)，其余方法走接口默认实现 */
    private final ChatModel stubModel = new ChatModel() {
        @Override
        public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("你好"))
                    .tokenUsage(new TokenUsage(120, 30))
                    .build();
        }
    };

    /** 分两块推同一个回答，用量在结束时一次性给出 —— 与真实端点的行为一致 */
    private final StreamingChatModel stubStreamingModel = new StreamingChatModel() {
        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            handler.onPartialResponse("你");
            handler.onPartialResponse("好");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("你好"))
                    .tokenUsage(new TokenUsage(120, 30))
                    .build());
        }
    };

    @Test
    void 一次同步调用应记录耗时与两个方向的token用量() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        provider(registry, null).chat(List.of(userMessage()), config());

        assertThat(registry.get("agent.llm.latency").tag("model", MODEL).tag("mode", "sync")
                .timer().count()).isEqualTo(1);

        assertThat(registry.get("agent.llm.tokens").tag("model", MODEL).tag("type", "prompt")
                .counter().count()).isEqualTo(120);
        assertThat(registry.get("agent.llm.tokens").tag("model", MODEL).tag("type", "completion")
                .counter().count()).isEqualTo(30);
    }

    /**
     * 流式与同步的标签必须隔离。
     * <p>
     * 混在一起算，首字延迟会被整轮时长污染——那样这个指标既看不出流式体验，
     * 也看不出同步调用的真实耗时。
     */
    @Test
    void 流式调用记在stream标签下且不污染sync() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        provider(registry, stubStreamingModel).chatStream(List.of(userMessage()), config(), chunk -> {
        });

        assertThat(registry.get("agent.llm.latency").tag("model", MODEL).tag("mode", "stream")
                .timer().count())
                .as("流式路径必须留下自己的耗时记录")
                .isEqualTo(1);
        assertThat(registry.find("agent.llm.latency").tag("mode", "sync").timer())
                .as("流式调用不该被记成同步")
                .isNull();
    }

    private LangChain4jLLMProvider provider(SimpleMeterRegistry registry, StreamingChatModel streaming) {
        return new LangChain4jLLMProvider(stubModel, streaming, new ToolRegistry(), config(), registry);
    }

    private LLMConfig config() {
        return LLMConfig.builder().model(MODEL).temperature(0.0).maxTokens(100).build();
    }

    private ChatMessage userMessage() {
        return ChatMessage.builder().role(ChatMessage.Role.USER).content("你好").build();
    }
}
