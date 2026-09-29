package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.loop.LoopBudget;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「查空了」必须能让执行图知道，并因此转起重规划那个环。
 * <p>
 * 这是自我纠偏的入口条件。执行图只有感知到「这一轮没拿到能作答的东西」才会回到
 * replan；而检索类工具不抛异常，它只是返回零条 —— 如果零条被当成成功，
 * 那个环就永远只在工具真报错时才转，等于没有。
 * <p>
 * 另一半同样要守：<b>查到东西的步骤不能被误判成没查到</b>。误判会让每一轮都重规划，
 * 白白多花一次模型调用。
 */
class AgentGraphReplanTest {

    private static final String TOOL = "search";
    private static final LLMConfig CONFIG = LLMConfig.builder().model("stub").build();

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 计划只产出一次，重规划那一次返回空计划 —— 用一个计数器数出「重规划有没有发生」 */
    private static final class StubProvider implements LLMProvider {
        final AtomicInteger planCalls = new AtomicInteger();
        final AtomicInteger planContextsWithNoDataHint = new AtomicInteger();

        @Override
        public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            return LLMResponse.builder().content("基于查到的数据作答").build();
        }

        @Override
        public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                         Map<String, Object> toolContext) {
            return LLMResponse.builder().content("基于查到的数据作答").build();
        }

        @Override
        public List<PlanStep> plan(String userMessage, List<ToolDefinition> availableTools, String context) {
            if (planCalls.incrementAndGet() == 1) {
                return List.of(PlanStep.builder()
                        .tool(TOOL)
                        .arguments(Map.of("query", "维生素D"))
                        .reason("找找看")
                        .build());
            }
            // 重规划的简报走第一个参数；重规划之外它只有用户的原始问题，不会含这个说法
            if (userMessage != null && userMessage.contains("无结果")) {
                planContextsWithNoDataHint.incrementAndGet();
            }
            return List.of();
        }
    }

    /** 一个「跑通了但什么都没查到」的工具：success 为真，noData 也为真 */
    private Tool toolReturning(ToolResult result) {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder()
                        .name(TOOL).description("搜索").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return result;
            }
        };
    }

    private AgentGraph.GraphResult run(ToolResult toolResult, StubProvider provider, LoopGuard guard) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(toolReturning(toolResult));
        AgentGraph graph = new AgentGraph(provider, CONFIG, registry, executor);
        return graph.run("u1", "有没有维生素D", "", List.of(), false, guard, null);
    }

    @Test
    void 查到数据的步骤不会触发重规划() {
        StubProvider provider = new StubProvider();

        AgentGraph.GraphResult result = run(
                ToolResult.builder().success(true).output("找到 3 个商品").build(),
                provider, new LoopGuard(new LoopBudget(8, 2, 2)));

        assertThat(provider.planCalls.get())
                .as("查到东西就该直接作答，多规划一轮是白花的模型调用")
                .isEqualTo(1);
        assertThat(result.getSteps()).hasSize(1);
        assertThat(result.getSteps().get(0).isNoData()).isFalse();
    }

    @Test
    void 无结果的步骤会触发重规划() {
        StubProvider provider = new StubProvider();
        LoopGuard guard = new LoopGuard(new LoopBudget(8, 2, 2));

        AgentGraph.GraphResult result = run(
                ToolResult.builder().success(true).output("没有找到与「维生素D」相关的商品。")
                        .noData(true).build(),
                provider, guard);

        assertThat(provider.planCalls.get())
                .as("查空了必须能被感知，否则 replan → act 这个环永远转不起来")
                .isEqualTo(2);
        assertThat(provider.planContextsWithNoDataHint.get())
                .as("重规划简报要写明「无结果」并给出换策略的指令 —— "
                        + "不写，模型最常见的反应是原样再查一次，花掉一轮换来同样的空结果")
                .isEqualTo(1);
        assertThat(result.getSteps().get(0).isNoData()).isTrue();
        assertThat(guard.planRounds()).isEqualTo(1);
    }

    @Test
    void 无结果也会被轮次预算收住不会无限重规划() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(toolReturning(ToolResult.builder()
                .success(true).output("没有找到相关商品").noData(true).build()));

        // 每次规划都产出同一个步骤，且每次都查空：没有轮次上限就是死循环
        LLMProvider alwaysPlans = new LLMProvider() {
            @Override
            public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
                return LLMResponse.builder().content("答").build();
            }

            @Override
            public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                             Map<String, Object> toolContext) {
                return LLMResponse.builder().content("答").build();
            }

            @Override
            public List<PlanStep> plan(String userMessage, List<ToolDefinition> availableTools, String context) {
                return List.of(PlanStep.builder().tool(TOOL).arguments(Map.of("query", "维生素D")).build());
            }
        };
        AgentGraph looping = new AgentGraph(alwaysPlans, CONFIG, registry, executor);
        LoopGuard guard = new LoopGuard(new LoopBudget(8, 2, 1));

        AgentGraph.GraphResult result = looping.run("u1", "有没有维生素D", "", List.of(), false, guard, null);

        assertThat(result.getAnswer()).isNotBlank();
        assertThat(guard.planRounds())
                .as("规划轮次必须被 LoopGuard 收住：空结果只是「该换策略了」，不是无限重试的理由")
                .isEqualTo(1);
        assertThat(result.getLoops()).contains("planRounds=1/1");
    }
}
