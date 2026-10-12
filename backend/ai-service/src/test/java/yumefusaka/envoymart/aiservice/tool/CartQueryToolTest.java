package yumefusaka.envoymart.aiservice.tool;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.core.AgentGraph;
import yumefusaka.envoymart.agent.core.TaskStage;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.model.AgentCartItem;
import yumefusaka.envoymart.common.result.Result;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 锁住购物车查询文字、结构化顺序与后续真实行 ID 的契约。 */
class CartQueryToolTest {
    private static final String USER = "u1";

    @Test
    void 两行商品按原顺序展示行ID规格SKU和实际数量() {
        var first = item(91L, 701L, "钙片", "60片", 3, 12000L, true);
        var second = item(12L, 702L, "鱼油", "120粒", 1, 9800L, false);
        var items = List.of(first, second);
        var client = clientReturning(items);

        var result = new CartQueryTool(client).execute(queryCall());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsSubsequence(
                "候选[0] 行ID=91 SKU=701：钙片（60片） × 3 = 120.00 元",
                "候选[1] 行ID=12 SKU=702：鱼油（120粒） × 1 = 98.00 元")
                .contains("合计：218.00 元", "当前不可购买");
        assertThat(result.getRawData()).isEqualTo(items);
        verify(client).listCart(USER);
        verifyNoMoreInteractions(client);
    }

    @Test
    void 空购物车的结构化返回仍是列表() {
        var result = new CartQueryTool(clientReturning(List.of())).execute(queryCall());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("购物车是空的。");
        assertThat(result.getRawData()).isEqualTo(List.of());
    }

    @Test
    void 下游空数据沿用空购物车语义但保持列表结构() {
        var result = new CartQueryTool(clientReturning(null)).execute(queryCall());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRawData()).isEqualTo(List.of());
    }

    @Test
    void 工具描述明确修改和删除使用行ID而非SKU() {
        assertThat(new CartQueryTool(mock(OrderClient.class)).getDefinition().getDescription())
                .contains("候选", "行 ID", "cartItemId", "id", "skuId");
    }

    @Test
    void 两行查询后的分别修改保留行ID与目标数量对应关系() {
        var client = clientReturning(List.of(item(91L, 701L, "钙片", "60片", 3, 12000L, true),
                item(12L, 702L, "鱼油", "120粒", 1, 9800L, true)));
        var registry = new ToolRegistry();
        registry.register(new CartQueryTool(client));
        registry.register(new CartUpdateTool(client));
        LLMProvider provider = new LLMProvider() {
            @Override public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
                return LLMResponse.builder().content("待确认").build();
            }
            @Override public List<PlanStep> plan(String message,
                    List<yumefusaka.envoymart.agent.tool.ToolDefinition> tools, String context) {
                return List.of(PlanStep.builder().tool("cart_query").arguments(Map.of()).build(),
                        PlanStep.builder().tool("cart_update")
                                .arguments(Map.of("cartItemId", "$0[0].id", "quantity", 2)).build(),
                        PlanStep.builder().tool("cart_update")
                                .arguments(Map.of("cartItemId", "$0[1].id", "quantity", 4)).build());
            }
        };
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var result = new AgentGraph(provider, LLMConfig.builder().model("stub").build(), registry, executor)
                    .run(USER, "第一行改2件，第二行改4件", "", List.of(), new LoopGuard(), null, null);

            assertThat(result.getPendingActions()).hasSize(2);
            assertThat(result.getPendingActions().get(0).arguments())
                    .containsEntry("cartItemId", 91L).containsEntry("quantity", 2);
            assertThat(result.getPendingActions().get(1).arguments())
                    .containsEntry("cartItemId", 12L).containsEntry("quantity", 4);
            assertThat(result.getStage()).isEqualTo(TaskStage.WAITING_USER);
            verify(client).listCart(USER);
            verifyNoMoreInteractions(client);
        }
    }

    private static OrderClient clientReturning(List<AgentCartItem> items) {
        var client = mock(OrderClient.class);
        when(client.listCart(USER)).thenReturn(Result.success(items));
        return client;
    }

    private static ToolCall queryCall() {
        return new ToolCall("query", "cart_query", Map.of(), false, USER);
    }

    private static AgentCartItem item(Long id, Long skuId, String name, String spec,
                                      int quantity, Long subtotal, boolean available) {
        var item = new AgentCartItem();
        item.setId(id);
        item.setSkuId(skuId);
        item.setName(name);
        item.setSpecText(spec);
        item.setQuantity(quantity);
        item.setSubtotal(subtotal);
        item.setAvailable(available);
        return item;
    }
}
