package yumefusaka.envoymart.aiservice.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import yumefusaka.envoymart.agent.core.AgentCancelledException;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.loop.LoopBudget;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.loop.ToolContextKeys;
import yumefusaka.envoymart.agent.tool.PendingAction;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具循环是否真的会执行工具 —— 这条是迁移的核心回归防线。
 * <p>
 * 迁到 LangChain4j 后循环由我们自己写，失败模式与在 Spring AI 下相反：那时要防的是
 * 「框架的 advisor 没挂上，tool_call 静默没人消费」；现在要防的是「循环写漏了某一步」——
 * 少执行一次工具、少回填一次结果、或者护栏判定挪出了循环，都会表现为模型答非所问，
 * 而不是报错。这类静默失效只能靠"断言工具被调用了"来防。
 */
class ReactToolLoopTest {

    private static final String MODEL = "stub-model";
    private static final String TOOL_NAME = "echo";

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicReference<String> executed = new AtomicReference<>();

    private final Tool echoTool = new Tool() {
        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.builder()
                    .name(TOOL_NAME)
                    .description("回显")
                    .parameters(Map.of())
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call) {
            executed.set(call.getToolName());
            return ToolResult.builder().success(true).output("工具返回值").build();
        }
    };

    /** 首次返回 tool_call，之后返回最终回答 —— 模拟 ReAct 的一轮往返 */
    private final ChatModel stubModel = new ChatModel() {
        @Override
        public ChatResponse chat(ChatRequest request) {
            if (modelCalls.incrementAndGet() == 1) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("", List.of(ToolExecutionRequest.builder()
                                .id("call-1").name(TOOL_NAME).arguments("{}").build())))
                        .build();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("最终回答")).build();
        }
    };

    private LangChain4jLLMProvider provider(ChatModel model) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool);
        return new LangChain4jLLMProvider(model, null, registry,
                LLMConfig.builder().model(MODEL).build(), new SimpleMeterRegistry());
    }

    private List<ChatMessage> messages() {
        return List.of(ChatMessage.builder().role(ChatMessage.Role.USER).content("你好").build());
    }

    @Test
    void 带工具循环的调用会执行模型请求的工具并二次调用模型() {
        LLMResponse response = provider(stubModel)
                .chatWithTools(messages(), LLMConfig.builder().model(MODEL).build(), Map.of());

        assertThat(executed.get())
                .as("模型发出了 tool_call，必须真的有人执行它")
                .isEqualTo(TOOL_NAME);
        assertThat(modelCalls.get())
                .as("执行完工具要把结果回填给模型再问一次，这才是完整的往返")
                .isEqualTo(2);
        assertThat(response.getContent())
                .as("返回的应是工具执行后的最终回答，而不是空内容")
                .isEqualTo("最终回答");
        assertThat(response.getToolExecutions())
                .as("工具轨迹要能回收，否则前端看不到这一段")
                .hasSize(1);
    }

    @Test
    void 单次调用不执行工具() {
        LLMResponse response = provider(stubModel)
                .chat(messages(), LLMConfig.builder().model(MODEL).build(), Map.of());

        assertThat(modelCalls.get())
                .as("规划、分类、抽取这类内部调用不该驱动工具循环")
                .isEqualTo(1);
        assertThat(executed.get())
                .as("单次调用路径上不下发工具定义，也绝不该执行它")
                .isNull();
        assertThat(response.getContent()).isEmpty();
    }

    /**
     * 护栏必须对工具循环生效。
     * <p>
     * 迁移前护栏要经 toolContext 下发到框架的 ToolCallback 里才能生效（那时它是"送进框架的"
     * 一件外物）；现在它就在循环体内。这条测试守的是迁完仍然生效——少了它，
     * 「预算」就只是文档里的说法。
     */
    @Test
    void 工具循环仍受我们的护栏约束() {
        AtomicInteger executions = new AtomicInteger();
        Tool counting = new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name(TOOL_NAME).description("计数").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                executions.incrementAndGet();
                return ToolResult.builder().success(true).output("ok").build();
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(counting);

        // 前两次都要求调用工具，第三次给最终回答
        AtomicInteger calls = new AtomicInteger();
        ChatModel greedy = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                if (calls.incrementAndGet() <= 2) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("", List.of(ToolExecutionRequest.builder()
                                    .id("c" + calls.get()).name(TOOL_NAME).arguments("{}").build())))
                            .build();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("结束")).build();
            }
        };

        LangChain4jLLMProvider provider = new LangChain4jLLMProvider(greedy, null, registry,
                LLMConfig.builder().model(MODEL).build(), new SimpleMeterRegistry());
        // 预算：整轮只允许 1 次工具调用
        LoopGuard guard = new LoopGuard(new LoopBudget(1, 2, 2));

        provider.chatWithTools(messages(), LLMConfig.builder().model(MODEL).build(),
                Map.of(ToolContextKeys.LOOP_GUARD, guard));

        assertThat(executions.get())
                .as("第二次工具调用必须被护栏拦下，否则预算形同虚设")
                .isEqualTo(1);
        assertThat(guard.getStopReason()).contains("工具调用总数");
    }

    /**
     * 预算耗尽后**循环本身**必须停下来，不能靠模型自己看拒绝提示收手。
     * <p>
     * 这条守的是一个具体的失败模式：护栏只装在「执行工具」这一点上，而循环由「模型往返」
     * 驱动。被拦下的调用走的是回填一条拒绝消息再转一圈，模型下一圈照样能要工具——
     * 于是预算耗尽之后，终止完全取决于模型是否配合。桩模型这里**永不收手**，
     * 正好把这个依赖暴露成一条会挂的断言：修之前它会一直转到测试超时。
     * <p>
     * 断言的是「模型被问了几次」而不是「工具被调用了几次」。后者在修复前也是对的
     * （护栏确实拦住了工具），差别只在模型还在被反复问——而那正是花钱的地方。
     */
    @Test
    @Timeout(10)
    void 预算耗尽后循环立刻收口而不是继续问模型() {
        AtomicInteger requests = new AtomicInteger();
        // 一个不配合的模型：只要给了工具定义就一定要工具，永远不会自己停
        ChatModel neverStops = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                requests.incrementAndGet();
                boolean toolsOffered = !request.toolSpecifications().isEmpty();
                if (toolsOffered) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("", List.of(ToolExecutionRequest.builder()
                                    .id("c" + requests.get()).name(TOOL_NAME).arguments("{}").build())))
                            .build();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("基于已有信息的回答")).build();
            }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool);

        LangChain4jLLMProvider provider = new LangChain4jLLMProvider(neverStops, null, registry,
                LLMConfig.builder().model(MODEL).build(), new SimpleMeterRegistry());
        // 只允许 2 次工具调用
        LoopGuard guard = new LoopGuard(new LoopBudget(2, 2, 2));

        LLMResponse response = provider.chatWithTools(messages(), LLMConfig.builder().model(MODEL).build(),
                Map.of(ToolContextKeys.LOOP_GUARD, guard));

        assertThat(guard.getStopReason()).contains("工具调用总数");
        assertThat(requests.get())
                .as("预算耗尽后必须撤掉工具定义收口，最多再多问一次；一直问下去就是无上限烧钱")
                .isLessThanOrEqualTo(4);
        assertThat(response.getContent())
                .as("收口那一问必须真的拿到回答，不能把一个空内容返回给上层")
                .isEqualTo("基于已有信息的回答");
    }

    /**
     * 调用方没传护栏时，循环也得有上限。
     * <p>
     * 循环的终止条件是这一层的责任，不能取决于每个调用点都记得传护栏——
     * 漏传一次就是一次没有上限的调用循环。桩模型同样永不收手，
     * 断言的是「它终究停下来了」，而不是停下来时用了几次。
     */
    @Test
    @Timeout(10)
    void 调用方没传护栏时循环仍有上限() {
        AtomicInteger requests = new AtomicInteger();
        ChatModel neverStops = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                requests.incrementAndGet();
                if (!request.toolSpecifications().isEmpty()) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("", List.of(ToolExecutionRequest.builder()
                                    .id("c" + requests.get()).name(TOOL_NAME).arguments("{}").build())))
                            .build();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("兜底回答")).build();
            }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool);

        LLMResponse response = new LangChain4jLLMProvider(neverStops, null, registry,
                LLMConfig.builder().model(MODEL).build(), new SimpleMeterRegistry())
                // 刻意不传 LOOP_GUARD
                .chatWithTools(messages(), LLMConfig.builder().model(MODEL).build(), Map.of());

        assertThat(requests.get())
                .as("没传护栏也要有默认预算兜住，否则这个循环是无上限的")
                .isLessThanOrEqualTo(11);
        assertThat(response.getContent()).isEqualTo("兜底回答");
    }

    /**
     * 撤掉工具定义之后模型仍然吐 tool_call —— 循环也不能转下去。
     * <p>
     * 这是把「循环一定会停」从<b>模型配合</b>手里收回到<b>代码</b>手里的最后一步。
     * 前面那条测试守的是合规 API：不给工具就不要工具。这一条守的是不合规的那半 ——
     * 有些模型会自己编 tool_call，提示注入也正是奔着这个去的。这时护栏只能拦住
     * <b>执行</b>，拦不住<b>往返</b>：一轮回填一条拒绝消息，下一轮模型再要一次，
     * 每一次都是真实计费的调用。所以必须有跟模型行为无关的硬上限。
     * <p>
     * 超时是断言的一部分：修复前这条不是失败，是<b>挂住</b>。
     */
    @Test
    @Timeout(10)
    void 撤掉工具定义后模型仍要工具也不会转成死循环() {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        Tool counting = new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name(TOOL_NAME).description("计数").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                executions.incrementAndGet();
                return ToolResult.builder().success(true).output("ok").build();
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(counting);

        // 不看有没有工具定义，一律吐 tool_call
        ChatModel alwaysAsks = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                requests.incrementAndGet();
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("", List.of(ToolExecutionRequest.builder()
                                .id("c" + requests.get()).name(TOOL_NAME).arguments("{}").build())))
                        .build();
            }
        };

        LangChain4jLLMProvider provider = new LangChain4jLLMProvider(alwaysAsks, null, registry,
                LLMConfig.builder().model(MODEL).build(), new SimpleMeterRegistry());
        LoopGuard guard = new LoopGuard(new LoopBudget(2, 2, 2));

        provider.chatWithTools(messages(), LLMConfig.builder().model(MODEL).build(),
                Map.of(ToolContextKeys.LOOP_GUARD, guard));

        assertThat(executions.get())
                .as("护栏该拦的仍然拦着，硬上限不是绕过护栏的后门")
                .isEqualTo(2);
        assertThat(requests.get())
                .as("往返次数由代码封顶（预算 2 + 余量 2），不随模型行为浮动")
                .isEqualTo(4);
    }

    // ==================== 高危确认（ReAct 路径） ====================

    /** 一个需要用户确认的高危工具，记录它被真正执行的次数 */
    private Tool cancelTool(AtomicInteger executions) {
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
                executions.incrementAndGet();
                return ToolResult.builder().success(true).output("订单已取消").build();
            }
        };
    }

    /** 永远要取消订单的模型——循环不中断它就会一直要下去 */
    private ChatModel greedyCanceller(AtomicInteger requests) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("", List.of(ToolExecutionRequest.builder()
                                .id("c" + requests.incrementAndGet())
                                .name("order_cancel").arguments("{\"orderId\":12}").build())))
                        .build();
            }
        };
    }

    /**
     * 高危工具在 ReAct 路径必须在<b>执行之前</b>被拦下 —— 用户没确认过的取消绝不能发出去。
     * <p>
     * ReAct 与计划路径的拦截位置相同（调用之前），时机不同：计划路径看得见整份计划、
     * 能在批次执行前拦；ReAct 无从预知模型要调什么，只能在它要出来之后、动手之前拦。
     * <p>
     * 拦下必须<b>中断整条循环</b>，而不是回填一条拒绝让模型绕着走——被拦的是
     * 「用户还没批准」，不是「模型想错了」，转一圈它只会换个说法再要一次，
     * 每一圈都是真实计费的调用。桩模型这里永不收手，正好把这个依赖暴露成断言：
     * 不中断的话模型会被反复问下去。
     * <p>
     * 还有一条同样重要：被拦的操作<b>不产生「失败」轨迹</b>。它根本没执行，
     * 记成 success=false 会让前端把它渲染成红色「失败」——把「等你批准」说成「出错了」，
     * 而且 pendingActions 为空时用户连确认卡都看不到，永远批不了这一次操作。
     * <p>
     * <b>拦截在这一层是无条件的</b>：这里没有「已确认」这个开关，循环也永远不会执行
     * 一个需要确认的工具。放行发生在确认轮，而确认轮不进这个循环——它由 Agent 层
     * 按签名载荷直接执行（见 {@code AgentApprovalTokenTest}）。所以这个用例只有
     * 「拦住」一种结局，不存在与之对应的「确认后循环放行」用例。
     */
    @Test
    @Timeout(10)
    void ReAct路径对高危工具执行前拦截并中断循环() {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(cancelTool(executions));

        LangChain4jLLMProvider provider = new LangChain4jLLMProvider(greedyCanceller(requests), null,
                registry, LLMConfig.builder().model(MODEL).build(), new SimpleMeterRegistry());
        List<PendingAction> pending = new ArrayList<>();

        LLMResponse response = provider.chatWithTools(messages(), LLMConfig.builder().model(MODEL).build(),
                Map.of(ToolContextKeys.LOOP_GUARD, new LoopGuard(new LoopBudget(8, 2, 2)),
                        ToolContextKeys.PENDING_ACTIONS, pending));

        assertThat(executions.get())
                .as("中断必须发生在执行之前——没确认过的取消绝不能真的发出去")
                .isZero();
        assertThat(pending)
                .as("拦下的操作要以结构化载荷交给调用方：确认令牌要签的就是它，"
                        + "只交一句描述的话签出来的令牌没有内容可绑")
                .containsExactly(PendingAction.of("order_cancel", Map.of("orderId", 12)));
        assertThat(pending.get(0).describe())
                .as("展示用的描述仍然要有，前端确认卡片靠它")
                .isEqualTo("order_cancel(orderId=12)");
        assertThat(requests.get())
                .as("拦下即收口：不能回填拒绝再转一圈，那不解决「用户没批准」")
                .isEqualTo(1);
        assertThat(response.getToolExecutions())
                .as("被拦的操作不是执行轨迹——报成失败会把「等你批准」说成「出错了」")
                .isEmpty();
    }

    // ==================== 取消（用户点了「停止生成」） ====================

    /**
     * 取消已生效：循环的第一次问话都不该发生。
     * <p>
     * 用户已经走了，之后每一次模型往返、每一次工具执行都是没有接收方的开销。
     * 检查点放在循环顶部（问话之前）而不是工具执行处，是因为「不再开始新工作」
     * 覆盖的正是「下一圈还会不会发生」——只拦工具不拦问话，账单照走。
     */
    @Test
    void 取消已生效时工具循环连模型都不问() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool);
        LangChain4jLLMProvider provider = new LangChain4jLLMProvider(stubModel, null, registry,
                LLMConfig.builder().model(MODEL).build(), new SimpleMeterRegistry());

        ToolProgressListener listener = new ToolProgressListener() {
            @Override
            public void onStart(String tool) {
            }

            @Override
            public void onFinish(String tool, boolean success, boolean noData, long latencyMs) {
            }

            @Override
            public boolean cancelled() {
                return true;
            }
        };

        assertThatThrownBy(() -> provider.chatWithTools(messages(), LLMConfig.builder().model(MODEL).build(),
                Map.of(ToolContextKeys.TOOL_PROGRESS, listener)))
                .satisfies(e -> assertThat(AgentCancelledException.isCancellation(e)).isTrue());

        assertThat(modelCalls.get())
                .as("取消后不该再发起任何计费调用")
                .isZero();
        assertThat(executed.get()).isNull();
    }

    /**
     * 模型<b>已经</b>要了工具、取消在工具真正动手之前到达 —— 工具必须一条都不执行。
     * <p>
     * 这是取消语义里最硬的一段：工具是唯一会跨出本进程、产生不可撤销副作用的动作。
     * 「模型要了」不等于「会执行」，判据落在执行之前；而同一批里如果第一条已经在跑，
     * 让它跑完（悬在半途的副作用比慢更糟），后面的不再放行。
     */
    @Test
    void 模型已请求工具但取消在工具执行前到达时不执行() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicInteger requests = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                requests.incrementAndGet();
                // 模型这一轮生成期间用户点了停止
                cancelled.set(true);
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("", List.of(ToolExecutionRequest.builder()
                                .id("call-1").name(TOOL_NAME).arguments("{}").build())))
                        .build();
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool);
        LangChain4jLLMProvider provider = new LangChain4jLLMProvider(model, null, registry,
                LLMConfig.builder().model(MODEL).build(), new SimpleMeterRegistry());

        ToolProgressListener listener = new ToolProgressListener() {
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
        };

        assertThatThrownBy(() -> provider.chatWithTools(messages(), LLMConfig.builder().model(MODEL).build(),
                Map.of(ToolContextKeys.TOOL_PROGRESS, listener)))
                .satisfies(e -> assertThat(AgentCancelledException.isCancellation(e)).isTrue());

        assertThat(executed.get())
                .as("模型要了工具不等于工具会被执行——没开始的副作用绝不放行")
                .isNull();
        assertThat(requests.get()).isEqualTo(1);
    }

    /**
     * 取消在<b>问话期间</b>到达：在途的那一轮跑完，但不能按「正常收尾」交出去。
     * <p>
     * 掉这一条的表现最隐蔽：循环顶的检查要等下一圈才执行，而这一轮已经拿到了
     * 「没有工具请求」的终局——循环直接 break 返回，取消旗不再有人看，
     * 一次用户按下的停止变成一轮完整作答。
     */
    @Test
    void 取消在问话期间到达时该轮跑完也不交出去() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                calls.incrementAndGet();
                // 这一轮生成期间用户点了停止
                cancelled.set(true);
                return ChatResponse.builder().aiMessage(AiMessage.from("整篇回答")).build();
            }
        };
        LangChain4jLLMProvider provider = new LangChain4jLLMProvider(model, null, new ToolRegistry(),
                LLMConfig.builder().model(MODEL).build(), new SimpleMeterRegistry());

        ToolProgressListener listener = new ToolProgressListener() {
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
        };

        assertThatThrownBy(() -> provider.chatWithTools(messages(), LLMConfig.builder().model(MODEL).build(),
                Map.of(ToolContextKeys.TOOL_PROGRESS, listener)))
                .satisfies(e -> assertThat(AgentCancelledException.isCancellation(e)).isTrue());

        assertThat(calls.get())
                .as("那一轮已经在途，拦不住也不必拦；拦的是把它当成正常结果交出去")
                .isEqualTo(1);
    }

    // ==================== 流式推送节奏 ====================

    /**
     * 流式正文必须<b>边到边推、且恰好推一次</b>。
     * <p>
     * 两个失败模式各钉一头：
     * <ul>
     *   <li><b>攒着不推</b>（把 chunk 收在轮次里、收尾一次性倒出）——体感是生成期间
     *       界面一片静止、结束时整篇砸下来，恰好不是对话界面的样子。断言放在回调现场：
     *       第一个 chunk 推出去的这一刻，消费者必须已经拿到它，而不是等收尾帧；</li>
     *   <li><b>推两遍</b>（live 推送与收尾回放同时存在）——屏幕上整篇正文重复一遍。
     *       收尾断言严格相等即覆盖。</li>
     * </ul>
     */
    @Test
    void 流式chunk边到边推且恰好一次() {
        List<String> received = new ArrayList<>();
        StreamingChatModel stubStream = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("你好");
                // 回调现场断言：这一刻消费者必须已经拿到——攒到收尾再倒出的话这里还是空
                assertThat(received)
                        .as("chunk 产生即推送，不是收尾时一次性回放")
                        .containsExactly("你好");
                handler.onPartialResponse("，世界");
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from("你好，世界"))
                        .tokenUsage(new TokenUsage(1, 2))
                        .build());
            }
        };
        LangChain4jLLMProvider provider = new LangChain4jLLMProvider(stubModel, stubStream,
                new ToolRegistry(), LLMConfig.builder().model(MODEL).build(), new SimpleMeterRegistry());

        provider.chatStream(messages(), LLMConfig.builder().model(MODEL).build(), received::add);

        assertThat(received)
                .as("每个 chunk 恰好推一次：live 推送与收尾回放若同时存在，整篇正文会被推两遍")
                .containsExactly("你好", "，世界");
    }

    /**
     * 取消在<b>正文轮期间</b>到达：轮次跑完（在途调用收不回），但收尾必须按取消走。
     * <p>
     * 这是「停止生成」最常见的时序——用户看着字往外冒，读到一半按了停止。少了
     * 收口这一步，这一轮会被当成正常作答返回：历史里记下整篇，屏幕上只有半句，
     * 下次打开会话回答「自己长长了」。
     */
    @Test
    void 取消在正文轮期间到达时整轮跑完也不按正常收尾返回() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        StreamingChatModel stubStream = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("用户看到的前半句");
                // 这一轮还在生成时用户点了停止
                cancelled.set(true);
                handler.onPartialResponse("推给空气的后半句");
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from("用户看到的前半句推给空气的后半句"))
                        .tokenUsage(new TokenUsage(1, 2))
                        .build());
            }
        };
        LangChain4jLLMProvider provider = new LangChain4jLLMProvider(stubModel, stubStream, new ToolRegistry(),
                LLMConfig.builder().model(MODEL).build(), new SimpleMeterRegistry());

        ToolProgressListener listener = new ToolProgressListener() {
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
        };

        assertThatThrownBy(() -> provider.chatStreamWithTools(messages(), LLMConfig.builder().model(MODEL).build(),
                Map.of(ToolContextKeys.TOOL_PROGRESS, listener), chunk -> {
                }))
                .satisfies(e -> assertThat(AgentCancelledException.isCancellation(e)).isTrue());
    }
}
