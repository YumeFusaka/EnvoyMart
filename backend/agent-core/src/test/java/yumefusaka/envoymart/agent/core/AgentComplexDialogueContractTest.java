package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** T1 复杂对话最小回放：同一会话不同请求不能串用旧步骤结果。 */
class AgentComplexDialogueContractTest {

    @Test
    void 同一会话重复相同问题每次请求生成新的operationId() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new Tool() {
                @Override
                public ToolDefinition getDefinition() {
                    return ToolDefinition.builder().name("lookup").description("查询").build();
                }

                @Override
                public ToolResult execute(ToolCall call) {
                    return ToolResult.builder().success(true).output("fresh").build();
                }
            });
            AgentGraph graph = new AgentGraph(new StubProvider(),
                    LLMConfig.builder().model("stub").build(), registry, executor);

            var first = graph.run("u1", "s1", "查一下", "", List.<ChatMessage>of(),
                    new LoopGuard(), null, null, AgentGraph.RetrievalResult::empty);
            var second = graph.run("u1", "s1", "查一下", "", List.<ChatMessage>of(),
                    new LoopGuard(), null, null, AgentGraph.RetrievalResult::empty);

            assertThat(first.getPlan()).hasSize(1);
            assertThat(second.getPlan()).hasSize(1);
            assertThat(first.getPlan().get(0).getOperationId())
                    .isNotEqualTo(second.getPlan().get(0).getOperationId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 模型自带的operationId不会绕过请求级命名空间() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new Tool() {
                @Override public ToolDefinition getDefinition() {
                    return ToolDefinition.builder().name("lookup").description("查询").build();
                }
                @Override public ToolResult execute(ToolCall call) {
                    return ToolResult.builder().success(true).output("fresh").build();
                }
            });
            AgentGraph graph = new AgentGraph(new LLMProvider() {
                @Override public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
                    return LLMResponse.builder().content("完成").build();
                }
                @Override public List<PlanStep> plan(String message, List<ToolDefinition> tools, String context) {
                    return List.of(PlanStep.builder().tool("lookup").operationId("model-fixed-id")
                            .arguments(Map.of()).build());
                }
            }, LLMConfig.builder().model("stub").build(), registry, executor);

            var result = graph.run("u1", "s1", "查一下", "", List.<ChatMessage>of(),
                    new LoopGuard(), null, null, AgentGraph.RetrievalResult::empty);

            assertThat(result.getPlan().get(0).getOperationId()).isNotEqualTo("model-fixed-id");
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class StubProvider implements LLMProvider {
        @Override
        public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            return LLMResponse.builder().content("完成").build();
        }

        @Override
        public List<PlanStep> plan(String message, List<ToolDefinition> tools, String context) {
            return List.of(PlanStep.builder().tool("lookup").arguments(Map.of()).reason("查询").build());
        }
    }
}
