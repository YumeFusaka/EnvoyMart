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
    private static class StubProvider implements LLMProvider {
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


        /** 会给出诊断的 provider：数出诊断调用了几次、重规划时有没有带上诊断结论 */
    private static final class CritiquingProvider extends StubProvider {
        final AtomicInteger critiqueCalls = new AtomicInteger();

        @Override
        public String critique(String userMessage, String executionTrace,
                               List<ToolDefinition> availableTools) {
            critiqueCalls.incrementAndGet();
            return "数据缺失：平台商品数据里没有该关键词对应的条目，应如实告知而非继续换词";
        }

        /**
         * 只记录、不改变 StubProvider 的计数语义——super.plan 自己会 ++planCalls，
         * 这里再数一次会让「第一次规划」被误判成第二次，计划直接变空、图一步都不走。
         */
        @Override
        public List<PlanStep> plan(String userMessage, List<ToolDefinition> availableTools, String context) {
            String recording = lastPlanUserMessage;
            List<PlanStep> result = super.plan(userMessage, availableTools, context);
            if (recording != null || planCalls.get() > 1) {
                lastPlanUserMessage = userMessage;
                lastPlanContext = context;
            }
            return result;
        }

        String lastPlanUserMessage;
        String lastPlanContext;
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
        return graph.run("u1", "有没有维生素D", "", List.of(), guard, null, null);
    }

    /**
     * 两步之间有依赖时，后一步的参数必须能拿到前一步查出来的<b>真实值</b>。
     * <p>
     * 这是「Agent 能不能做复杂任务」的枢纽：查订单拿到商品 → 按商品查说明 →
     * 按规格加购，每一步的入参都来自上一步的输出。计划是静态 JSON，表达不了
     * 「这个值是前面算出来的」，只能留下一个符号；把它变成值的动作发生在执行期。
     * <p>
     * <b>断言的是「工具收到的入参」而不是「最终回答」</b>：占位串能被模型在生成阶段
     * 顺口解释过去，而入参是它改写不了的执行事实。
     */
    @Test
    void 后一步的参数能取到前一步的输出() {
        AtomicInteger secondStepArg = new AtomicInteger(-1);
        Tool first = new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("lookup").description("查").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                // rawData 模拟业务 DTO：一个带 getter 的对象，字段名是 skuId
                return ToolResult.builder().success(true).output("SKU29").rawData(new Sku(29L)).build();
            }
        };
        Tool second = new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("add").description("加").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                secondStepArg.set(Integer.parseInt(String.valueOf(call.getArguments().get("skuId"))));
                return ToolResult.builder().success(true).output("已加入").build();
            }
        };

        LLMProvider provider = new LLMProvider() {
            @Override
            public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
                return LLMResponse.builder().content("ok").build();
            }

            @Override
            public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                             Map<String, Object> toolContext) {
                return LLMResponse.builder().content("ok").build();
            }

            @Override
            public List<PlanStep> plan(String userMessage, List<ToolDefinition> availableTools, String context) {
                return List.of(
                        PlanStep.builder().tool("lookup").arguments(Map.of()).reason("先查").build(),
                        PlanStep.builder().tool("add")
                                .arguments(Map.of("skuId", "$0.skuId", "note", "订单 $0.skuId 的货"))
                                .dependsOn(List.of(0)).reason("再加").build());
            }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(first);
        registry.register(second);
        AgentGraph graph = new AgentGraph(provider, CONFIG, registry, executor);
        graph.run("u1", "帮我加购", "", List.of(), new LoopGuard(new LoopBudget(8, 2, 2)), null, null);

        assertThat(secondStepArg.get())
                .as("$0.skuId 必须被换成上一步 rawData 里的真实值 29；"
                        + "换不掉时它会原样传下去，工具拿到一串占位文本然后失败")
                .isEqualTo(29);
    }

    @Test
    void 多元素列表未带下标时阻断后续写步骤() {
        AtomicInteger writes = new AtomicInteger();
        Tool search = new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("lookup").description("查").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.builder().success(true).output("两个商品")
                        .rawData(List.of(new Sku(29L), new Sku(30L))).build();
            }
        };
        Tool write = new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("add").description("写").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                writes.incrementAndGet();
                return ToolResult.builder().success(true).output("写入").build();
            }
        };
        LLMProvider provider = new LLMProvider() {
            @Override public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
                return LLMResponse.builder().content("已阻断").build();
            }
            @Override public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                                        Map<String, Object> context) {
                return LLMResponse.builder().content("已阻断").build();
            }
            @Override public List<PlanStep> plan(String message, List<ToolDefinition> tools, String context) {
                return List.of(
                        PlanStep.builder().tool("lookup").arguments(Map.of()).build(),
                        PlanStep.builder().tool("add").arguments(Map.of("skuId", "$0.skuId"))
                                .dependsOn(List.of(0)).build());
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(search);
        registry.register(write);
        AgentGraph graph = new AgentGraph(provider, CONFIG, registry, executor);

        AgentGraph.GraphResult result = graph.run("u1", "双商品都处理", "", List.of(),
                new LoopGuard(new LoopBudget(8, 2, 2)), null, null);

        assertThat(writes).hasValue(0);
        assertThat(result.getAnswer()).contains("阻止");
    }

    /**
     * 高危拦截必须<b>只拦当前这一批</b>，不能把整份计划一次拦下。
     * <p>
     * 实测踩过：计划是「先 product_search 拿 skuId，再 cart_add 用它」，而拦截逻辑
     * 扫的是整份计划——第一批就把还没轮到的 cart_add 拦了。后果有两层：
     * 第一步永远没执行，卡片上的参数因此是没解析的引用串（"$0.skuId"）；
     * 用户即便点确认，拿到的也必然是一次失败。**拦截粒度必须与执行粒度一致**。
     */
    @Test
    void 高危拦截只拦当前批次不会挡掉前序步骤() {
        AtomicInteger searchCalls = new AtomicInteger();
        Tool search = new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("lookup").description("查").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                searchCalls.incrementAndGet();
                return ToolResult.builder().success(true).output("SKU29").rawData(new Sku(29L)).build();
            }
        };
        Tool risky = new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("add").description("加")
                        .requiresConfirmation(true).parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.builder().success(true).output("不该在拦截轮执行").build();
            }
        };

        LLMProvider provider = new LLMProvider() {
            @Override
            public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
                return LLMResponse.builder().content("ok").build();
            }

            @Override
            public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                             Map<String, Object> toolContext) {
                return LLMResponse.builder().content("ok").build();
            }

            @Override
            public List<PlanStep> plan(String userMessage, List<ToolDefinition> availableTools, String context) {
                return List.of(
                        PlanStep.builder().tool("lookup").arguments(Map.of()).reason("先查").build(),
                        PlanStep.builder().tool("add").arguments(Map.of("skuId", "$0.skuId"))
                                .dependsOn(List.of(0)).reason("再加").build());
            }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(search);
        registry.register(risky);
        AgentGraph graph = new AgentGraph(provider, CONFIG, registry, executor);
        AgentGraph.GraphResult result =
                graph.run("u1", "先搜再加购", "", List.of(), new LoopGuard(new LoopBudget(8, 2, 2)), null, null);

        assertThat(searchCalls.get())
                .as("第一步必须真的跑过：不跑，第二步引用的值就取不到，卡片上是模板串")
                .isEqualTo(1);
        assertThat(result.getPendingActions()).hasSize(1);
        assertThat(result.getPendingActions().get(0).arguments().get("skuId"))
                .as("卡片上要显示的是解析后的真实 skuId，否则用户不知道自己在批准什么")
                .isEqualTo(29L);
    }

    /** 供引用解析测试用的最小业务对象：字段名与 DTO 的 getter 约定一致 */
    public static class Sku {
        private final Long skuId;

        public Sku(Long skuId) {
            this.skuId = skuId;
        }

        public Long getSkuId() {
            return skuId;
        }
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
    void 重规划前会先做一次自我诊断并把它带进规划上下文() {
        CritiquingProvider provider = new CritiquingProvider();

        run(ToolResult.builder().success(true).output("没有找到与「维生素D」相关的商品。")
                .noData(true).build(), provider, new LoopGuard(new LoopBudget(8, 2, 2)));

        assertThat(provider.critiqueCalls.get())
                .as("查空了要先归一次因，否则重规划只会把上一步原样再试一遍")
                .isEqualTo(1);
        String passed = provider.lastPlanUserMessage == null ? "" : provider.lastPlanUserMessage;
        assertThat(passed)
                .as("诊断结论必须真的进到重规划上下文里 —— 算了不用等于没算；实际收到：" + passed)
                .contains("失败诊断");
    }

    @Test
    void 查到数据时不做诊断不白花模型调用() {
        CritiquingProvider provider = new CritiquingProvider();

        run(ToolResult.builder().success(true).output("找到 3 个商品").build(),
                provider, new LoopGuard(new LoopBudget(8, 2, 2)));

        assertThat(provider.critiqueCalls.get())
                .as("顺利拿到数据就没有失败可诊断，多调一次是纯浪费")
                .isZero();
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

        AgentGraph.GraphResult result = looping.run("u1", "有没有维生素D", "", List.of(), guard, null, null);

        assertThat(result.getAnswer()).isNotBlank();
        assertThat(guard.planRounds())
                .as("规划轮次必须被 LoopGuard 收住：空结果只是「该换策略了」，不是无限重试的理由")
                .isEqualTo(1);
        assertThat(result.getLoops()).contains("planRounds=1/1");
    }
}
