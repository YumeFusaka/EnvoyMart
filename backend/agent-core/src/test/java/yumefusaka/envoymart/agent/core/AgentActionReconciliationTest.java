package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.loop.LoopBudget;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.tool.PendingAction;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.agent.tool.WriteFingerprints;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动作级一致性：把「用户要几件事」变成可核对的账。
 * <p>
 * 现场是 {@code chat:hist:u1001:u1001-1791529067350}：用户说「那两个商品我都来一个吧」，
 * 续作轮的 {@code core_intent} 写着「把免疫配方版加入购物车」，而落地的确认载荷
 * 仍是上一批那个 {@code skuId=39}（原版）——同一个 SKU 被加购第二次，
 * 用户第 13 轮亲自质问才发现。
 * <p>
 * 两层各锁一件事：
 * <ul>
 *   <li><b>同批不折叠</b>：一次批准里几条高危步骤就得有几条载荷。
 *       曾经整批只留一条，用户以为「两件都批了」，实际只批了一件；</li>
 *   <li><b>续作不重复</b>：待确认批次的操作目标若与已经执行过的写操作相同，系统拒绝生成载荷。
 *       这一条必须由<b>系统</b>拒绝——「在提示词里告诉模型别重复」和「让系统拒绝重复」
 *       是两件事，前者已经做了，模型可以不听。</li>
 * </ul>
 */
class AgentActionReconciliationTest {

    private static final LLMConfig CONFIG = LLMConfig.builder().model("stub").build();

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 首次规划给一份计划，重规划一律返回空 —— 每个用例只该看到一轮执行 */
    private static class FixedPlanProvider implements LLMProvider {
        private final List<PlanStep> plan;

        FixedPlanProvider(List<PlanStep> plan) {
            this.plan = plan;
        }

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
        public List<PlanStep> plan(String message, List<ToolDefinition> tools, String context) {
            return List.of();
        }

        @Override
        public yumefusaka.envoymart.agent.llm.PlanWithIntent planWithIntent(
                String message, List<ToolDefinition> tools, String context) {
            return new yumefusaka.envoymart.agent.llm.PlanWithIntent(plan, false);
        }
    }

    /** 首次规划给第一份计划，重规划给第二份 —— 用来验证「拦下之后模型还改得动」 */
    private static class ReplanProvider implements LLMProvider {
        private final List<List<PlanStep>> plans;
        private final AtomicInteger planCalls = new AtomicInteger();

        ReplanProvider(List<List<PlanStep>> plans) {
            this.plans = plans;
        }

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
        public List<PlanStep> plan(String message, List<ToolDefinition> tools, String context) {
            int call = planCalls.incrementAndGet();
            return call <= plans.size() ? plans.get(call - 1) : List.of();
        }

        @Override
        public yumefusaka.envoymart.agent.llm.PlanWithIntent planWithIntent(
                String message, List<ToolDefinition> tools, String context) {
            planCalls.incrementAndGet();
            return new yumefusaka.envoymart.agent.llm.PlanWithIntent(
                    plans.isEmpty() ? List.of() : plans.get(0), false);
        }
    }

    private static Tool lookup(String name, long skuId) {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name(name).description("检索").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.builder().success(true).output("SKU" + skuId)
                        .rawData(new AgentGraphReplanTest.Sku(skuId)).build();
            }
        };
    }

    private static Tool cartAdd(AtomicInteger writes) {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("cart_add").description("加购")
                        .requiresConfirmation(true).parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                writes.incrementAndGet();
                return ToolResult.builder().success(true).output("已加购").build();
            }
        };
    }

    private static Tool orderQuery(AtomicInteger calls) {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("order_query").description("查订单")
                        .parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                calls.incrementAndGet();
                return ToolResult.builder().success(true).output("订单已发货").build();
            }
        };
    }

    // ==================== 第 2 点：同批动作不得折叠 ====================

    /**
     * 四步计划：两次检索各拿一个 SKU，两条加购各自依赖自己那一次检索。
     * <p>
     * 这条锁的是「一次批准 = 用户要的每一件都在卡片上」。曾经这里只留一条载荷，
     * 用户以为两件都批了、实际只批了一件，而<b>系统全程没有任何地方发现少了一件</b>。
     */
    @Test
    void 同批两条高危步骤不得折叠成一条() {
        AtomicInteger writes = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(lookup("lookup_a", 29L));
        registry.register(lookup("lookup_b", 39L));
        registry.register(cartAdd(writes));

        FixedPlanProvider provider = new FixedPlanProvider(List.of(
                PlanStep.builder().tool("lookup_a").arguments(Map.of("query", "免疫配方版")).reason("查A").build(),
                PlanStep.builder().tool("lookup_b").arguments(Map.of("query", "原版120片")).reason("查B").build(),
                PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", "$0.skuId"))
                        .dependsOn(List.of(0)).reason("加购A").build(),
                PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", "$1.skuId"))
                        .dependsOn(List.of(1)).reason("加购B").build()));

        AgentGraph.GraphResult result = new AgentGraph(provider, CONFIG, registry, executor).run(
                "u1", "s1", "那两个商品我都来一个吧", "", List.of(),
                new LoopGuard(new LoopBudget(8, 2, 2)), null, null,
                AgentGraph.RetrievalResult::empty, List.of());

        assertThat(result.getPendingActions())
                .as("用户要两件，确认卡片上就必须有两件——少一件时用户以为已经批准了")
                .hasSize(2);
        assertThat(result.getPendingActions())
                .extracting(action -> action.arguments().get("skuId"))
                .as("两条载荷必须指向各自的 SKU：都填同一个等于把两件合成一件")
                .containsExactlyInAnyOrder(29L, 39L);
        assertThat(writes)
                .as("拦截轮不得产生任何真实副作用")
                .hasValue(0);
    }

    // ==================== 第 3 点：续作轮参数对账 ====================

    /**
     * 续作轮把已执行过的 SKU 又填了一遍 —— 必须被系统拒绝。
     * <p>
     * 指纹由 {@link WriteFingerprints} 生成，与产品代码里判定用的是<b>同一个函数</b>：
     * 两端各拼一份拼法，会出现「两串永远不相等、判重永远不命中」这种查不出来的失效。
     */
    @Test
    void 续作轮填回已执行的SKU必须被拦下() {
        AtomicInteger writes = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(cartAdd(writes));

        FixedPlanProvider provider = new FixedPlanProvider(List.of(
                PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 39))
                        .reason("把免疫配方版加入购物车").build()));
        List<String> executed = List.of(WriteFingerprints.of("cart_add", Map.of("skuId", 39)));

        AgentGraph.GraphResult result = new AgentGraph(provider, CONFIG, registry, executor).run(
                "u1", "s1", "免疫配方那款也来一个", "", List.of(),
                new LoopGuard(new LoopBudget(8, 2, 2)), null, null,
                AgentGraph.RetrievalResult::empty, executed);

        assertThat(result.getPendingActions())
                .as("同一个 SKU 的第二次加购必须被系统拒绝，而不是靠模型自觉")
                .isNullOrEmpty();
        assertThat(writes).hasValue(0);
    }

    /** 判据的边界：目标不同就必须放行，否则「续作」这件事本身做不成了 */
    @Test
    void 续作轮填入不同SKU必须放行() {
        AtomicInteger writes = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(cartAdd(writes));

        FixedPlanProvider provider = new FixedPlanProvider(List.of(
                PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 29))
                        .reason("把免疫配方版加入购物车").build()));
        List<String> executed = List.of(WriteFingerprints.of("cart_add", Map.of("skuId", 39)));

        AgentGraph.GraphResult result = new AgentGraph(provider, CONFIG, registry, executor).run(
                "u1", "s1", "免疫配方那款也来一个", "", List.of(),
                new LoopGuard(new LoopBudget(8, 2, 2)), null, null,
                AgentGraph.RetrievalResult::empty, executed);

        assertThat(result.getPendingActions())
                .as("上一批加的是 39，这一批要加 29 —— 这正是续作该做的事，不能拦")
                .hasSize(1);
        assertThat(result.getPendingActions().get(0).arguments().get("skuId")).isEqualTo(29);
        assertThat(writes).hasValue(0);
    }

    /**
     * 数量变了但目标没变，仍然是同一件商品 —— 判重按目标算，不按整份参数算。
     * <p>
     * 数量参与指纹的话，「再来一件」会被放行成第二次加购，而用户看到的正是重复。
     */
    @Test
    void 同一SKU改数量仍算重复() {
        AtomicInteger writes = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(cartAdd(writes));

        FixedPlanProvider provider = new FixedPlanProvider(List.of(
                PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 39, "quantity", 2))
                        .reason("再加一件").build()));
        List<String> executed = List.of(WriteFingerprints.of("cart_add", Map.of("skuId", 39, "quantity", 1)));

        AgentGraph.GraphResult result = new AgentGraph(provider, CONFIG, registry, executor).run(
                "u1", "s1", "再来一个", "", List.of(),
                new LoopGuard(new LoopBudget(8, 2, 2)), null, null,
                AgentGraph.RetrievalResult::empty, executed);

        assertThat(result.getPendingActions()).isNullOrEmpty();
    }

    /** 判据只覆盖高危步骤：只读调用天然会重复，纳入判重会把正常链路拦下 */
    @Test
    void 只读调用重复不受判重影响() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(orderQuery(calls));

        FixedPlanProvider provider = new FixedPlanProvider(List.of(
                PlanStep.builder().tool("order_query").arguments(Map.of("orderId", 22)).reason("查一次").build(),
                PlanStep.builder().tool("order_query").arguments(Map.of("orderId", 22)).reason("再查一次").build()));
        List<String> executed = List.of(WriteFingerprints.of("order_query", Map.of("orderId", 22)));

        AgentGraph.GraphResult result = new AgentGraph(provider, CONFIG, registry, executor).run(
                "u1", "s1", "我的订单到哪了", "", List.of(),
                new LoopGuard(new LoopBudget(8, 2, 2)), null, null,
                AgentGraph.RetrievalResult::empty, executed);

        assertThat(calls)
                .as("同一订单查两次是正常行为，判重不该碰只读路径")
                .hasValue(2);
        assertThat(result.getPendingActions()).isNullOrEmpty();
    }

    /**
     * 没有目标编号的写操作不参与判重。
     * <p>
     * 下单的参数是收货信息与备注，拿整份参数比会把「加了新商品后用同样地址再下一单」
     * 误判成重复——误拦挡住的是用户合法的第二次操作，比漏拦更糟。
     */
    @Test
    void 没有目标编号的写操作不参与判重() {
        AtomicInteger writes = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("cart_checkout").description("下单")
                        .requiresConfirmation(true).parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                writes.incrementAndGet();
                return ToolResult.builder().success(true).output("已下单").build();
            }
        });

        Map<String, Object> arguments = Map.of("receiverName", "张三", "receiverPhone", "13800000000");
        FixedPlanProvider provider = new FixedPlanProvider(List.of(
                PlanStep.builder().tool("cart_checkout").arguments(arguments).reason("下单").build()));
        List<String> executed = WriteFingerprints.ofAll(List.of(
                PendingAction.of("cart_checkout", arguments)));

        AgentGraph.GraphResult result = new AgentGraph(provider, CONFIG, registry, executor).run(
                "u1", "s1", "用这个地址下单", "", List.of(),
                new LoopGuard(new LoopBudget(8, 2, 2)), null, null,
                AgentGraph.RetrievalResult::empty, executed);

        assertThat(executed)
                .as("没有目标编号时指纹为空 —— 空指纹不参与判重是这条判据的边界")
                .isEmpty();
        assertThat(result.getPendingActions())
                .as("下单没有可判重的目标，不能因为地址相同就拦下用户的新订单")
                .hasSize(1);
    }

    // ==================== 拦下之后必须改得动 ====================

    /**
     * 指纹列表里的空位不该打穿主链路。
     * <p>
     * 调用方拼列表时少填一项是很可能发生的事，而 {@code List.copyOf} 遇到 null 元素
     * 会直接抛 NPE——一次防御性列表的瑕疵就把整轮对话变成 500，代价与收益完全不成比例。
     */
    @Test
    void 指纹列表含空位不影响正常请求() {
        AtomicInteger writes = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(cartAdd(writes));

        FixedPlanProvider provider = new FixedPlanProvider(List.of(
                PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 29))
                        .reason("加购").build()));
        List<String> executed = java.util.Arrays.asList(null,
                WriteFingerprints.of("cart_add", Map.of("skuId", 39)), null);

        AgentGraph.GraphResult result = new AgentGraph(provider, CONFIG, registry, executor).run(
                "u1", "s1", "加一件", "", List.of(),
                new LoopGuard(new LoopBudget(8, 2, 2)), null, null,
                AgentGraph.RetrievalResult::empty, executed);

        assertThat(result.getPendingActions())
                .as("空位应当被忽略，而不是把整轮请求变成异常")
                .hasSize(1);
    }

    /**
     * 被拦下不是死胡同：模型换用真实编号后必须能拿到正确载荷。
     * <p>
     * <b>只断言「第一次被拒」的话，一个把模型逼进死胡同的实现同样能通过。</b>
     * 所以这里要求第二轮真的走通（{@code requests == 2}）。
     */
    @Test
    void 拦下后模型换用真实编号仍能拿到载荷() {
        AtomicInteger writes = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(cartAdd(writes));

        ReplanProvider provider = new ReplanProvider(List.of(
                List.of(PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 39))
                        .reason("把免疫配方版加入购物车").build()),
                List.of(PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 29))
                        .reason("把免疫配方版加入购物车").build())));
        List<String> executed = List.of(WriteFingerprints.of("cart_add", Map.of("skuId", 39)));

        AgentGraph.GraphResult result = new AgentGraph(provider, CONFIG, registry, executor).run(
                "u1", "s1", "免疫配方那款也来一个", "", List.of(),
                new LoopGuard(new LoopBudget(8, 2, 2)), null, null,
                AgentGraph.RetrievalResult::empty, executed);

        assertThat(result.getPendingActions())
                .as("改对目标之后必须能拿到载荷，否则这条判据就是个死胡同")
                .hasSize(1);
        assertThat(result.getPendingActions().get(0).arguments().get("skuId")).isEqualTo(29);
        assertThat(result.getSteps())
                .as("被拦下的那一步要留痕，且原因要能指导改法")
                .anySatisfy(step -> assertThat(step.getOutput())
                        .contains("重复的写操作")
                        .contains("请先用查询工具取得它真实的编号"));
        assertThat(writes).hasValue(0);
    }
}
