package yumefusaka.envoymart.aiservice.service;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.client.ProductClient;
import yumefusaka.envoymart.aiservice.memory.ChatHistoryStore;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.OrderItemResponse;
import yumefusaka.envoymart.contract.OrderResponse;
import yumefusaka.envoymart.contract.SkuSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;

class CommerceCardAssemblerTest {
    private final ProductClient products = mock(ProductClient.class);
    private final OrderClient orders = mock(OrderClient.class);
    private final CommerceCardAssembler cards = new CommerceCardAssembler(products, orders);

    @Test
    void 跨轮加购无需本轮搜索输出也能批量补齐多个真实商品() {
        when(products.skus(List.of(10L, 27L))).thenReturn(Result.success(List.of(
                sku(10L, "益生菌粉", "规格:60 袋", 27800L),
                sku(27L, "叶黄素酯软胶囊", null, 15800L))));
        List<Map<String, Object>> original = List.of(add(10L, 2), add(27L, 1));

        List<Map<String, Object>> enriched = cards.approvalDetails(original);

        assertThat(arguments(enriched.getFirst())).containsEntry("productName", "益生菌粉")
                .containsEntry("specification", "规格:60 袋")
                .containsEntry("unitPrice", 27800L).containsEntry("subtotal", 55600L);
        assertThat(arguments(enriched.get(1))).containsEntry("productName", "叶黄素酯软胶囊")
                .containsEntry("specification", "单一规格");
        assertThat(arguments(original.getFirst())).containsOnlyKeys("skuId", "quantity");
        verify(products).skus(List.of(10L, 27L));
    }

    @Test
    void 商品服务失败时保留原执行参数并明确不能盲签() {
        when(products.skus(List.of(10L))).thenThrow(new IllegalStateException("不可用"));

        Map<String, Object> detail = cards.approvalDetails(List.of(add(10L, 1))).getFirst();

        assertThat(detail).containsEntry("confirmable", false).containsKey("displayNotice");
        assertThat(arguments(detail)).containsOnlyKeys("skuId", "quantity");
    }

    @Test
    void 下架商品仍显示资料但不可确认() {
        SkuSnapshot snapshot = sku(10L, "益生菌粉", "规格:60 袋", 27800L);
        snapshot.setSpuStatus(0);
        when(products.skus(List.of(10L))).thenReturn(Result.success(List.of(snapshot)));

        Map<String, Object> detail = cards.approvalDetails(List.of(add(10L, 1))).getFirst();

        assertThat(detail).containsEntry("confirmable", false);
        assertThat(arguments(detail)).containsEntry("productName", "益生菌粉");
    }

    @Test
    void 历史缺失卡从当前目录与本人订单快照补齐且不改原记录() {
        when(products.skus(List.of(10L))).thenReturn(Result.success(List.of(
                sku(10L, "益生菌粉", "规格:60 袋", 27800L))));
        OrderItemResponse item = OrderItemResponse.builder().skuId(10L).spuName("下单时的益生菌粉")
                .skuSpecText("规格:60 袋").unitPrice(27000L).quantity(1).subtotal(27000L).build();
        when(orders.getOrder("alice", 649L)).thenReturn(Result.success(OrderResponse.builder()
                .id(649L).orderNo("YS649").items(List.of(item)).build()));
        Map<String, Object> response = Map.of("approvalToken", "签名原文",
                "pendingActionDetails", List.of(add(10L, 1)),
                "pendingPayments", List.of(Map.of("orderId", 649L, "orderNo", "YS649", "payAmount", 27000L)));
        var stored = new ChatHistoryStore.StoredMessage("消息", "assistant", "原文", Instant.now(), response);

        var enriched = cards.history("alice", List.of(stored)).getFirst();

        assertThat(enriched.response()).containsEntry("approvalToken", "签名原文");
        assertThat(details(enriched.response(), "pendingActionDetails").getFirst()).containsKey("displayNotice");
        assertThat(details(enriched.response(), "pendingPayments").getFirst().get("items")).isEqualTo(List.of(item));
        assertThat(details(response, "pendingPayments").getFirst()).doesNotContainKey("items");
        verify(orders).getOrder("alice", 649L);
    }

    @Test
    void 数量为空时按加购工具默认值展示一件() {
        when(products.skus(List.of(10L))).thenReturn(Result.success(List.of(sku(10L, "益生菌粉", "60 袋", 27800L))));
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("skuId", 10L);
        arguments.put("quantity", null);

        Map<String, Object> detail = cards.approvalDetails(List.of(Map.of("tool", "cart_add", "arguments", arguments))).getFirst();

        assertThat(arguments(detail)).containsEntry("quantity", 1L).containsEntry("subtotal", 27800L);
        assertThat(detail).containsEntry("confirmable", true);
    }

    @Test
    void 超过交易服务单次数量上限时不允许确认() {
        SkuSnapshot snapshot = sku(10L, "益生菌粉", "60 袋", 27800L);
        snapshot.setStock(1000);
        when(products.skus(List.of(10L))).thenReturn(Result.success(List.of(snapshot)));

        Map<String, Object> detail = cards.approvalDetails(List.of(add(10L, 100))).getFirst();

        assertThat(detail).containsEntry("confirmable", false);
        assertThat(detail.get("displayNotice").toString()).contains("99");
    }

    @Test
    void 历史补查遇到订单服务不可用时不逐笔等待超时() {
        when(orders.getOrder("alice", 1L)).thenThrow(new IllegalStateException("不可用"));
        var stored = new ChatHistoryStore.StoredMessage("消息", "assistant", "原文", Instant.now(),
                Map.of("pendingPayments", List.of(Map.of("orderId", 1L, "orderNo", "YS1"),
                        Map.of("orderId", 2L, "orderNo", "YS2"))));

        assertThat(cards.history("alice", List.of(stored))).hasSize(1);

        verify(orders).getOrder("alice", 1L);
        verify(orders, never()).getOrder("alice", 2L);
    }

    @Test
    void 历史已保存完整商品快照时不使用当前价格覆盖() {
        Map<String, Object> detail = Map.of("tool", "cart_add", "arguments", Map.of(
                "skuId", 10L, "quantity", 1, "productName", "原商品名", "specification", "60 袋",
                "unitPrice", 27000L, "subtotal", 27000L));
        var stored = new ChatHistoryStore.StoredMessage("消息", "assistant", "原文", Instant.now(),
                Map.of("pendingActionDetails", List.of(detail)));

        var enriched = cards.history("alice", List.of(stored)).getFirst();

        assertThat(details(enriched.response(), "pendingActionDetails")).containsExactly(detail);
        verify(products, never()).skus(anyList());
    }

    private static Map<String, Object> add(long skuId, int quantity) {
        return Map.of("tool", "cart_add", "arguments", Map.of("skuId", skuId, "quantity", quantity));
    }

    private static SkuSnapshot sku(long id, String name, String spec, long price) {
        return SkuSnapshot.builder().id(id).spuName(name).specText(spec).price(price)
                .status(1).spuStatus(1).stock(100).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(Map<String, Object> detail) {
        return (Map<String, Object>) detail.get("arguments");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> details(Map<String, Object> response, String field) {
        return (List<Map<String, Object>>) response.get(field);
    }
}
