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
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具实时进度事件：界面「正在查询商品」这一步的依据。
 * <p>
 * 要守三件事：
 * <ul>
 *   <li><b>顺序</b>——start 必须在 finish 之前，界面先亮「正在执行」再落到结果态；</li>
 *   <li><b>结果语义</b>——查空（success=true, noData=true）必须把 noData 带出来，
 *       否则界面会把「没有找到」渲染成绿色的「成功」，与二态渲染是同一个坑；</li>
 *   <li><b>不发假事件</b>——被循环护栏拦下的调用没有真正执行，一条事件都不该出现，
 *       否则用户看到一个从未发生过的「正在执行」。</li>
 * </ul>
 */
class AgentGraphToolProgressTest {

    private static final String TOOL = "search";
    private static final LLMConfig CONFIG = LLMConfig.builder().model("stub").build();

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 只在首次规划产出一个步骤，重规划一律返回空 —— 每个用例只该看到一轮工具执行 */
    private static final class StubProvider implements LLMProvider {
        private final java.util.concurrent.atomic.AtomicInteger planCalls =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            return LLMResponse.builder().content("作答").build();
        }

        @Override
        public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                         Map<String, Object> toolContext) {
            return LLMResponse.builder().content("作答").build();
        }

        @Override
        public List<PlanStep> plan(String userMessage, List<ToolDefinition> availableTools, String context) {
            if (planCalls.incrementAndGet() > 1) {
                return List.of();
            }
            return List.of(PlanStep.builder()
                    .tool(TOOL)
                    .arguments(Map.of("query", "维生素D"))
                    .reason("找找看")
                    .build());
        }
    }

    /** 记录每一次回调的监听器；顺序即断言对象 */
    private static final class RecordingListener implements ToolProgressListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onStart(String tool) {
            events.add("start:" + tool);
        }

        @Override
        public void onFinish(String tool, boolean success, boolean noData, long latencyMs) {
            events.add("finish:" + tool + ":" + success + ":" + noData);
        }
    }

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

    private RecordingListener run(ToolResult toolResult, LoopGuard guard) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(toolReturning(toolResult));
        AgentGraph graph = new AgentGraph(new StubProvider(), CONFIG, registry, executor);
        RecordingListener listener = new RecordingListener();
        graph.run("u1", "有没有维生素D", "", List.of(), guard, null, listener);
        return listener;
    }

    @Test
    void 查到数据时_start_先于_finish_且_noData_为假() {
        RecordingListener listener = run(
                ToolResult.builder().success(true).output("找到 3 个商品").latencyMs(42).build(),
                new LoopGuard(new LoopBudget(8, 2, 2)));

        assertThat(listener.events).containsExactly(
                "start:" + TOOL,
                "finish:" + TOOL + ":true:false");
    }

    @Test
    void 查空时_finish_带出_noData() {
        RecordingListener listener = run(
                ToolResult.builder().success(true).noData(true).output("没有找到").build(),
                new LoopGuard(new LoopBudget(8, 2, 2)));

        assertThat(listener.events).containsExactly(
                "start:" + TOOL,
                "finish:" + TOOL + ":true:true");
    }

    @Test
    void 护栏拦下不发事件() {
        // 预算为 1 且先被别的调用用掉：图上这次调用必被护栏拒绝，工具从未真正执行
        LoopGuard guard = new LoopGuard(new LoopBudget(1, 2, 2));
        guard.allowToolCall("other", Map.of());

        RecordingListener listener = run(
                ToolResult.builder().success(true).output("找到 3 个商品").build(), guard);

        assertThat(listener.events)
                .as("被拦下的调用没有真正执行，界面不该出现「正在执行」")
                .isEmpty();
    }
}
