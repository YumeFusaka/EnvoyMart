package yumefusaka.envoymart.aiservice.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.core.AgentGraph;
import yumefusaka.envoymart.agent.core.TaskStage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.tool.ProductSearchResult;

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
    void 非法依赖保留原始下标交给计划编译器统一拒绝() {
        List<PlanStep> steps = planOf("""
                [{"tool":"order_query","arguments":{},"dependsOn":[0,5,-1,1]},
                 {"tool":"order_cancel","arguments":{},"dependsOn":[0]}]
                """);

        assertThat(steps.get(0).getDependsOn())
                .as("解析器不得静默剔除约束，编译器必须看到非法依赖才能拒绝并修复计划")
                .containsExactly(0, 5, -1, 1);
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

    @Test
    void 缺少工具的步骤必须保留避免引用序号漂移() {
        var steps = planOf("""
                [{"arguments":{}},
                 {"tool":"order_query","arguments":{}},
                 {"tool":"order_cancel","arguments":{"orderId":"$1.id"},"dependsOn":[1]}]
                """);

        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).getTool()).isNull();
        assertThat(steps.get(2).getDependsOn()).containsExactly(1);
    }

    @Test
    void 非整数依赖不能被截断成合法下标() {
        var steps = planOf("""
                [{"tool":"order_query","arguments":{}},
                 {"tool":"order_cancel","arguments":{},"dependsOn":[0.5,"0",4294967296]}]
                """);

        assertThat(steps.get(1).getDependsOn()).containsExactly(-1, -1, -1);
    }

    @Test
    void 非列表依赖不能静默视为无依赖() {
        var steps = planOf("""
                [{"tool":"order_query","arguments":{},"dependsOn":"0"}]
                """);

        assertThat(steps.get(0).getDependsOn()).containsExactly(-1);
    }

    @Test
    void 真实规划请求说明商品嵌套规格引用与选择边界() {
        var requestSeen = new java.util.concurrent.atomic.AtomicReference<ChatRequest>();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                requestSeen.set(request);
                return ChatResponse.builder().aiMessage(AiMessage.from("[]")).build();
            }
        };
        var provider = new LangChain4jLLMProvider(model, null, new ToolRegistry(),
                LLMConfig.builder().model("stub").build(), new SimpleMeterRegistry());
        provider.plan("加购选定规格", List.of(ToolDefinition.builder()
                .name("product_search").description("搜索").parameters(Map.of()).build()), "");

        assertThat(requestSeen.get().messages().get(0).toString())
                .contains("$0[0].skus[1].skuId", "多规格时顶层 skuId 为空", "不得默认选第一项",
                        "cartItemId 必须取目标行的 id", "$0[0].id", "$0[1].id",
                        "$N 只指向本轮计划步骤", "不猜 ID", "先澄清目标");
    }

    @Test
    void 待确认工具的审批契约实际进入规划请求() {
        var requestSeen = new java.util.concurrent.atomic.AtomicReference<ChatRequest>();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                requestSeen.set(request);
                return ChatResponse.builder().aiMessage(AiMessage.from("[]")).build();
            }
        };
        var provider = new LangChain4jLLMProvider(model, null, new ToolRegistry(),
                LLMConfig.builder().model("stub").build(), new SimpleMeterRegistry());
        provider.plan("选择乳清蛋白香草1kg加购1件，等我确认", List.of(ToolDefinition.builder()
                .name("cart_add").description("加购").requiresConfirmation(true).parameters(Map.of()).build()), "");
        assertThat(requestSeen.get().messages().get(0).toString())
                .contains("系统审批：调用只生成待审批操作", "必须把相应写工具放进计划", "不能只查询后口头询问",
                        "第二个规格", "旧列表第2款查回后通常是本轮搜索的第0条", "多SKU而未选规格时仍必须澄清");
    }

    @Test
    void 真实provider计划能从真实商品DTO解析选定规格形成审批卡() {
        var registry = new ToolRegistry();
        var selected = new ProductSearchResult.SkuOption(30L, "60粒", 8900L, 8, true);
        registry.register(new Tool() {
            @Override public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("product_search").description("查商品").build();
            }
            @Override public ToolResult execute(ToolCall call) {
                return ToolResult.builder().success(true).output("两个规格")
                        .rawData(List.of(new ProductSearchResult(null, null, null, null, null, null,
                                List.of(new ProductSearchResult.SkuOption(29L, "30粒", 4900L, 5, true), selected))))
                        .build();
            }
        });
        registry.register(new Tool() {
            @Override public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("cart_add").description("加购")
                        .requiresConfirmation(true).build();
            }
            @Override public ToolResult execute(ToolCall call) {
                throw new AssertionError("未审批不得执行加购");
            }
        });
        ChatModel model = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("""
                        {"plan":[{"tool":"product_search","arguments":{}},
                          {"tool":"cart_add","arguments":{"skuId":"$0[0].skus[1].skuId","quantity":1}}],
                         "needRetrieval":false}
                        """)).build();
            }
        };
        var config = LLMConfig.builder().model("stub").build();
        var provider = new LangChain4jLLMProvider(model, null, registry, config, new SimpleMeterRegistry());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var result = new AgentGraph(provider, config, registry, executor).run("u1", "第二规格加购", "",
                    List.of(), new LoopGuard(), null, null);

            assertThat(result.getPendingActions()).hasSize(1);
            assertThat(result.getPendingActions().get(0).arguments())
                    .containsEntry("skuId", selected.skuId()).containsEntry("quantity", 1);
            assertThat(result.getStage()).isEqualTo(TaskStage.WAITING_USER);
        }
    }
}
