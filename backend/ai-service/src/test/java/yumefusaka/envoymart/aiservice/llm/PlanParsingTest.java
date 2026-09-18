package yumefusaka.envoymart.aiservice.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行计划的解析 —— 重点是 {@code dependsOn}。
 * <p>
 * 早先规划提示词里根本没有这个字段，解析也不读它，于是它永远是空列表：
 * 「无依赖的步骤并发」成立，「按依赖分层、有依赖等前置完成」是死代码。
 * 只读工具时看不出问题，一旦出现"先查后改"的链路就会并发地去改同一条数据。
 */
class PlanParsingTest {

    private LangChain4jLLMProvider providerReturning(String json) {
        ChatModel stub = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(json)).build();
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("order_query").description("查订单").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.builder().success(true).output("ok").build();
            }
        });
        registry.register(new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("order_cancel").description("取消").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.builder().success(true).output("ok").build();
            }
        });
        return new LangChain4jLLMProvider(stub, null, registry,
                LLMConfig.builder().model("stub").build(), new SimpleMeterRegistry());
    }

    private List<PlanStep> planOf(String json) {
        return providerReturning(json).plan("取消订单 3", List.of(
                ToolDefinition.builder().name("order_query").description("查订单").parameters(Map.of()).build(),
                ToolDefinition.builder().name("order_cancel").description("取消").parameters(Map.of()).build()
        ), "");
    }

    @Test
    void 依赖下标被正确解析() {
        List<PlanStep> steps = planOf("""
                [{"tool":"order_query","arguments":{"orderId":3},"reason":"先查","dependsOn":[]},
                 {"tool":"order_cancel","arguments":{"orderId":3},"reason":"再取消","dependsOn":[0]}]
                """);

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getDependsOn()).isEmpty();
        assertThat(steps.get(1).getDependsOn()).containsExactly(0);
    }

    @Test
    void 非法依赖下标被剔除() {
        List<PlanStep> steps = planOf("""
                [{"tool":"order_query","arguments":{},"dependsOn":[0,5,-1,1]},
                 {"tool":"order_cancel","arguments":{},"dependsOn":[0]}]
                """);

        assertThat(steps.get(0).getDependsOn())
                .as("指向自己会让分层执行空转，指向后面会让它成环，都不该带进执行阶段")
                .isEmpty();
        assertThat(steps.get(1).getDependsOn()).containsExactly(0);
    }

    @Test
    void 缺少依赖字段时按无依赖处理() {
        List<PlanStep> steps = planOf("""
                [{"tool":"order_query","arguments":{"orderId":1}},
                 {"tool":"order_query","arguments":{"orderId":2}}]
                """);

        assertThat(steps).hasSize(2);
        assertThat(steps).allSatisfy(step -> assertThat(step.getDependsOn()).isEmpty());
    }
}
