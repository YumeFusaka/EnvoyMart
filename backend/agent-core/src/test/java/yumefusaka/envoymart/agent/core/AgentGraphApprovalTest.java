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
import yumefusaka.envoymart.agent.loop.ToolContextKeys;
import yumefusaka.envoymart.agent.tool.PendingAction;
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
 * 高危操作的中断出口：<b>撞上就必须停下来，且交出的要是「要执行什么」而不是一句关于它的话。</b>
 * <p>
 * 这条链路原先只有前半截——图确实会中断，但返回给调用方的是工具名 {@code order_cancel}
 * 三个字。用户看不到要取消的是哪一单，这个「确认」就只是走个形式；
 * 前端也无从渲染出一张有内容的确认卡片。
 * <p>
 * <b>图里没有「已确认」这个开关</b>，这是这一版最重要的结构变化：确认后要执行的那批调用
 * 不再重新经过图（模型重规划一遍，执行什么全看它这次想出什么），而是由服务端按签名载荷
 * 直接执行。所以这里只有「拦住」一种结局，放行那半截的测试在
 * {@link AgentApprovalTokenTest}——它守的是用户批准的那一次调用与真正执行的那一次是同一个。
 */
class AgentGraphApprovalTest {

    private static final LLMConfig CONFIG = LLMConfig.builder().model("stub").build();

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicInteger cancellations = new AtomicInteger();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 只产出一个「取消订单 12」的高危步骤 */
    private static final class StubProvider implements LLMProvider {

        @Override
        public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            return LLMResponse.builder().content("好的").build();
        }

        @Override
        public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                         Map<String, Object> toolContext) {
            return LLMResponse.builder().content("好的").build();
        }

        @Override
        public List<PlanStep> plan(String userMessage, List<ToolDefinition> availableTools, String context) {
            return List.of(PlanStep.builder()
                    .tool("order_cancel")
                    .arguments(Map.of("orderId", 12))
                    .reason("帮用户取消订单")
                    .build());
        }
    }

    private Tool cancelTool() {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder()
                        .name("order_cancel")
                        .description("取消未支付的订单。不可撤销，需要用户确认。")
                        .requiresConfirmation(true)
                        .parameters(Map.of())
                        .build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                cancellations.incrementAndGet();
                return ToolResult.builder().success(true).output("订单已取消").build();
            }
        };
    }

    private AgentGraph.GraphResult run() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(cancelTool());
        AgentGraph graph = new AgentGraph(new StubProvider(), CONFIG, registry, executor);
        return graph.run("u1", "帮我取消订单 12", "", List.of(),
                new LoopGuard(new LoopBudget(8, 2, 2)), null, null);
    }

    @Test
    void 撞上高危操作即中断并交出带参数的操作载荷() {
        AgentGraph.GraphResult result = run();

        assertThat(result.getPendingActions())
                .as("要给的是「取消哪一单」，只回一个工具名等于让用户盲签")
                .hasSize(1);
        assertThat(result.getPendingActions().get(0).tool()).isEqualTo("order_cancel");
        assertThat(result.getPendingActions().get(0).arguments())
                .as("载荷必须带参数：确认要签的是这一次调用本身，签名不能签一个空壳")
                .containsEntry("orderId", 12);
        assertThat(result.getPendingActions().get(0).describe())
                .isEqualTo("order_cancel(orderId=12)");
        assertThat(cancellations.get())
                .as("中断必须发生在调用工具之前")
                .isZero();
        assertThat(result.getAnswer())
                .as("图停在 END，没走 answer 节点 —— 此时没有回答可给，"
                        + "调用方应当把 pendingActions 渲染成确认提示")
                .isNull();
    }

    @Test
    void 描述里的参数按key排序() {
        PlanStep step = PlanStep.builder()
                .tool("order_cancel")
                .arguments(Map.of("orderId", 12, "reason", "用户要求"))
                .build();

        assertThat(AgentGraph.pendingAction(step).describe())
                .as("顺序不固定的话，同一份计划每次拼出的文本都不同，日志对比与断言都得先做集合比较")
                .isEqualTo("order_cancel(orderId=12, reason=用户要求)");
    }

    @Test
    void 没有参数时退化为工具名() {
        PlanStep bare = PlanStep.builder().tool("order_cancel").build();

        assertThat(AgentGraph.pendingAction(bare).describe()).isEqualTo("order_cancel");
        assertThat(AgentGraph.pendingAction(PlanStep.builder()
                .tool("order_cancel").arguments(Map.of()).build()).describe())
                .isEqualTo("order_cancel");
    }

    /**
     * 参数里带 null 是模型给得出来的（JSON 里写个 null 就行）。
     * <p>
     * 载荷要进签名，构造期就得容得下它——等到签发那一刻才炸，症状是
     * 「模型偶尔取消不了订单」，而堆栈指向的地方与真正的原因隔着好几层。
     */
    @Test
    void 参数值为null时载荷照常构造() {
        Map<String, Object> arguments = new java.util.HashMap<>();
        arguments.put("orderId", null);

        assertThat(PendingAction.of("order_cancel", arguments).describe())
                .as("Map.copyOf 在这一步会直接抛，所以构造器必须自己兜住 null 值")
                .isEqualTo("order_cancel(orderId=null)");
    }

    /**
     * ReAct 路径拦下的高危操作，同样要经 {@code pendingActions} 出口交到调用方手里。
     * <p>
     * 计划路径在批次执行前就看得见要拦什么；ReAct 只有等模型把工具要出来才知道，
     * 拦截发生在工具循环内部，拦下的调用经 toolContext 的 sink 回到图。
     * 这条测试守的是后半截管道：<b>sink 里的东西必须出现在 GraphResult.pendingActions 里</b>——
     * 少了这一支，Agent 层看不到它，用户拿到的是一个没有确认卡的中断，
     * 回复还是那句「抱歉，我没能完成这个请求」，而操作永远批不了。
     */
    @Test
    void ReAct路径拦下的高危操作也经pendingActions出口交给调用方() {
        // 模拟 ReAct 路径：计划为空 → answer 节点 → converse → chatWithTools，
        // 循环在里面拦住高危操作（写 sink、这轮没有最终回答）
        LLMProvider reactProvider = new LLMProvider() {
            @Override
            public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
                return LLMResponse.builder().content("好的").build();
            }

            @Override
            public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                             Map<String, Object> toolContext) {
                @SuppressWarnings("unchecked")
                List<PendingAction> sink = (List<PendingAction>) toolContext.get(ToolContextKeys.PENDING_ACTIONS);
                sink.add(PendingAction.of("order_cancel", Map.of("orderId", 12)));
                return LLMResponse.builder().content("").build();
            }

            @Override
            public List<PlanStep> plan(String userMessage, List<ToolDefinition> availableTools, String context) {
                return List.of();
            }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(cancelTool());
        AgentGraph graph = new AgentGraph(reactProvider, CONFIG, registry, executor);
        AgentGraph.GraphResult result = graph.run("u1", "帮我取消订单 12", "", List.of(),
                new LoopGuard(new LoopBudget(8, 2, 2)), null, null);

        assertThat(result.getPendingActions())
                .as("ReAct 拦下的操作必须走到与计划路径同一个出口，否则前端没有确认卡可渲染")
                .hasSize(1);
        assertThat(result.getPendingActions().get(0).describe()).isEqualTo("order_cancel(orderId=12)");
        assertThat(cancellations.get())
                .as("拦截发生之后工具仍不该被执行")
                .isZero();
    }
}
