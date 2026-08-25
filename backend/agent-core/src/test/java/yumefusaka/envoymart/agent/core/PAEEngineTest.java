package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PAEEngineTest {

    /** 只回显固定内容的假模型，避免测试依赖真实 LLM。 */
    private static LLMProvider stubProvider(List<PlanStep> plan) {
        return new LLMProvider() {
            @Override
            public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
                return LLMResponse.builder().content("合成回答").build();
            }

            @Override
            public List<PlanStep> plan(String userMessage, List<ToolDefinition> tools, String context) {
                return plan;
            }
        };
    }

    private static ToolRegistry echoRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("echo").description("回显").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.builder().success(true).output("echo-ok").build();
            }
        });
        return registry;
    }

    private PAEEngine engine(LLMProvider provider) {
        return new PAEEngine(provider, LLMConfig.builder().model("stub").build(), echoRegistry(), 5);
    }

    @Test
    void 计划引用的未注册工具会被丢弃() {
        LLMProvider provider = stubProvider(List.of(
                PlanStep.builder().tool("not_registered").build(),
                PlanStep.builder().tool("echo").build()));

        var result = engine(provider).execute("测试", List.of());

        assertThat(result.getSteps()).hasSize(1);
        assertThat(result.getSteps().get(0).getAction()).isEqualTo("echo");
        assertThat(result.getSteps().get(0).isSuccess()).isTrue();
    }

    @Test
    void 全部工具都不存在时返回可执行的兜底文案() {
        LLMProvider provider = stubProvider(List.of(PlanStep.builder().tool("ghost").build()));

        var result = engine(provider).execute("测试", List.of());

        assertThat(result.getSteps()).isEmpty();
        assertThat(result.getFinalAnswer()).contains("没有可用的工具");
    }

    @Test
    void 工具结果会交给模型合成最终回答() {
        LLMProvider provider = stubProvider(List.of(PlanStep.builder().tool("echo").build()));

        var result = engine(provider).execute("测试", List.of());

        assertThat(result.getFinalAnswer()).isEqualTo("合成回答");
        assertThat(result.getToolExecutions()).hasSize(1);
        assertThat(result.getToolExecutions().get(0).getOutput()).isEqualTo("echo-ok");
    }
}
