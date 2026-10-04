package yumefusaka.envoymart.aiservice.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型调用 trace（U13）的埋点行为。
 * <p>
 * <b>口径是"留了通道"</b>：导出开关仍默认关，这里验的不是"导出了 span"，
 * 而是"span 被创建了、attributes 带对了"。这几条断言是防止以后重构把埋点悄悄丢掉的
 * 唯一自动化依据——埋点丢了不会有任何报错，只会让"这次会话为什么花了 5 分钱"
 * 彻底无从回答。
 */
class ModelTracingTest {

    private final ChatModel stubModel = new ChatModel() {
        @Override
        public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("你好"))
                    .tokenUsage(new TokenUsage(120, 30))
                    .build();
        }
    };

    /** 收集所有走完的观测，供断言读取名字与 attributes */
    static final class CapturingHandler implements ObservationHandler<Observation.Context> {
        final List<Observation.Context> completed = new ArrayList<>();

        @Override
        public void onStop(Observation.Context context) {
            completed.add(context);
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }

    @Test
    void 一次同步对话模型调用应留下带模型与token的观测() {
        CapturingHandler handler = new CapturingHandler();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(handler);

        new LangChain4jLLMProvider(stubModel, null, new ToolRegistry(), config(),
                new SimpleMeterRegistry(), registry)
                .chat(List.of(userMessage()), config());

        Observation.Context context = handler.completed.stream()
                .filter(c -> "model.chat".equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有创建 model.chat 观测"));

        assertThat(context.getLowCardinalityKeyValue("model").getValue()).isEqualTo("stub-model");
        assertThat(context.getLowCardinalityKeyValue("mode").getValue()).isEqualTo("sync");
        assertThat(context.getHighCardinalityKeyValue("promptTokens").getValue()).isEqualTo("120");
        assertThat(context.getHighCardinalityKeyValue("completionTokens").getValue()).isEqualTo("30");
    }

    @Test
    void 未注入registry时不创建观测且调用照常() {
        // 不传 registry：Observation 退化为无操作，功能不受影响
        assertThat(new LangChain4jLLMProvider(stubModel, null, new ToolRegistry(), config(),
                new SimpleMeterRegistry())
                .chat(List.of(userMessage()), config()).getContent())
                .isEqualTo("你好");
    }

    private LLMConfig config() {
        return LLMConfig.builder().model("stub-model").temperature(0.0).maxTokens(100).build();
    }

    private ChatMessage userMessage() {
        return ChatMessage.builder().role(ChatMessage.Role.USER).content("你好").build();
    }
}