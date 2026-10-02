package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.AfterEach;
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
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 执行图的取消语义：<b>「不再开始新工作」，不是「打断进行中的调用」。</b>
 * <p>
 * 用户点了「停止生成」之后，服务端继续跑完整张计划不会有任何报错——模型继续计费、
 * 工具继续落副作用，而接收方早已不存在。这类缺陷从结果上看不出来（结果本来就没人看），
 * 所以每个用例都钉住一个「不该发生的事」：
 * <ul>
 *   <li>取消已生效 → 规划（一次计费调用）不发起；</li>
 *   <li>计划已产出、执行前取消 → 工具一次都不执行（副作用零发生）；</li>
 *   <li>计划为空、回答生成前取消 → 最贵的那次调用不发起。</li>
 * </ul>
 * 另一个隐性契约由 {@link #assertCancellation} 守着：取消异常要能<b>穿过执行框架的
 * 包装</b>被认出来——认不出来就会被上层当成故障，用户按的停止变成一句「暂时不可用」。
 */
class AgentCancellationTest {

    private static final String TOOL = "search";
    private static final LLMConfig CONFIG = LLMConfig.builder().model("stub").build();

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicInteger toolRuns = new AtomicInteger();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 取消旗可控的监听器；plan 与 act 之间翻转它，模拟「规划期间用户点了停止」 */
    private static final class CancellableListener implements ToolProgressListener {
        final AtomicBoolean cancelled = new AtomicBoolean();

        CancellableListener(boolean initiallyCancelled) {
            cancelled.set(initiallyCancelled);
        }

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
    }

    /** 只产出一份计划；{@code afterPlan} 在 plan 返回之前执行——取消只能在这一瞬到达 */
    private static final class StubProvider implements LLMProvider {
        final AtomicInteger planCalls = new AtomicInteger();
        final AtomicInteger chatCalls = new AtomicInteger();
        private final List<PlanStep> plan;
        private final Runnable afterPlan;

        StubProvider(List<PlanStep> plan, Runnable afterPlan) {
            this.plan = plan;
            this.afterPlan = afterPlan;
        }

        @Override
        public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            chatCalls.incrementAndGet();
            return LLMResponse.builder().content("作答").build();
        }

        @Override
        public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                         Map<String, Object> toolContext) {
            chatCalls.incrementAndGet();
            return LLMResponse.builder().content("作答").build();
        }

        @Override
        public List<PlanStep> plan(String userMessage, List<ToolDefinition> availableTools, String context) {
            planCalls.incrementAndGet();
            if (afterPlan != null) {
                afterPlan.run();
            }
            return plan;
        }
    }

    private static List<PlanStep> oneStep() {
        return List.of(PlanStep.builder()
                .tool(TOOL)
                .arguments(Map.of("query", "维生素D"))
                .reason("找找看")
                .build());
    }

    private void run(LLMProvider provider, ToolProgressListener listener) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder()
                        .name(TOOL).description("搜索").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                toolRuns.incrementAndGet();
                return ToolResult.builder().success(true).output("找到 1 个商品").build();
            }
        });
        AgentGraph graph = new AgentGraph(provider, CONFIG, registry, executor);
        graph.run("u1", "有没有维生素D", "", List.of(), new LoopGuard(), null, listener);
    }

    /**
     * 取消异常必须能被识别——哪怕执行框架（LangGraph4j）把它包过一层。
     * 认不出来的后果不是「抛得难看」，是被上层的降级分支接住，
     * 把用户自己按的停止记成一次系统故障。
     */
    private static void assertCancellation(Throwable e) {
        assertThat(AgentCancelledException.isCancellation(e))
                .as("取消信号要穿过执行框架的包装：%s", e)
                .isTrue();
    }

    @Test
    void 取消已生效时规划都不发起() {
        CancellableListener listener = new CancellableListener(true);
        StubProvider provider = new StubProvider(oneStep(), null);

        assertThatThrownBy(() -> run(provider, listener)).satisfies(AgentCancellationTest::assertCancellation);

        assertThat(provider.planCalls)
                .as("规划是一次真实计费的模型调用，取消后不该发起")
                .hasValue(0);
        assertThat(toolRuns).as("计划都没产出，工具更不该跑").hasValue(0);
    }

    @Test
    void 取消在规划与执行之间到达时工具一次都不执行() {
        CancellableListener listener = new CancellableListener(false);
        // plan 内部翻转旗标：计划已经产出，但执行之前取消到达
        StubProvider provider = new StubProvider(oneStep(), () -> listener.cancelled.set(true));

        assertThatThrownBy(() -> run(provider, listener)).satisfies(AgentCancellationTest::assertCancellation);

        assertThat(provider.planCalls).hasValue(1);
        assertThat(toolRuns)
                .as("计划虽然在手，取消之后一条副作用都不许落下")
                .hasValue(0);
    }

    @Test
    void 取消后连回答生成也不发起() {
        CancellableListener listener = new CancellableListener(false);
        // 计划为空 → 图会转入直接对话；取消在这一步之前到达，最贵的那次调用必须被拦下
        StubProvider provider = new StubProvider(List.of(), () -> listener.cancelled.set(true));

        assertThatThrownBy(() -> run(provider, listener)).satisfies(AgentCancellationTest::assertCancellation);

        assertThat(provider.chatCalls)
                .as("回答生成带着全部上下文，是这一轮最贵的调用，取消后不该发起")
                .hasValue(0);
    }
}
