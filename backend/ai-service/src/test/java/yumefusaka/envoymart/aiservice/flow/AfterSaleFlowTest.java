package yumefusaka.envoymart.aiservice.flow;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import yumefusaka.envoymart.agent.flow.FlowContext;
import yumefusaka.envoymart.agent.flow.FlowResult;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.AfterSalePreview;
import yumefusaka.envoymart.contract.OrderItemResponse;
import yumefusaka.envoymart.contract.OrderResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 售后流程的规则判定 —— 重点是<b>误判</b>场景与<b>结论的来源</b>。
 * <p>
 * 规则命中的后果是绕过模型直接回答，误判会让用户拿到答非所问的结果且没有兜底；
 * 漏判还有执行图接着。所以这里的用例大多是"不该命中"的反例。
 * <p>
 * <b>另一半用例是"这个结论是谁给的"。</b>这个类早先自己写过一条退货规则——实际等价于
 * 「只要不是已取消就能退」——它与页面上的判定是两套，同一个订单会出现两种说法。
 * 判定权现在在 order-service 的政策引擎，这里只允许复述它给的结论，
 * 所以下面大量用例是拿一个"我说了不算"的预览结果去断言流程怎么说话。
 */
class AfterSaleFlowTest {

    private final OrderClient orderClient = mock(OrderClient.class);
    private final AfterSaleFlow flow = new AfterSaleFlow(orderClient);

    private FlowContext contextWithOrder(String status, String message) {
        return contextWithOrder(status, message, null);
    }

    private FlowContext contextWithOrder(String status, String message, List<OrderItemResponse> items) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("order_query").description("查订单")
                        .parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                OrderResponse order = OrderResponse.builder()
                        .id(1L)
                        .orderNo("YS20260909")
                        .status(status)
                        .statusText("已收货")
                        .payAmount(9900L)
                        .items(items)
                        .build();
                return ToolResult.builder().success(true).output("订单状态: " + status).rawData(order).build();
            }
        });
        return FlowContext.builder()
                .userId("u1001").sessionId("s1").userMessage(message)
                .toolRegistry(registry).build();
    }

    private static List<OrderItemResponse> oneItem() {
        return List.of(OrderItemResponse.builder()
                .id(77L).spuName("深海鱼油软胶囊").unitPrice(9900L).quantity(1).subtotal(9900L).build());
    }

    /** 让判定服务给出一个结论；传 null 表示这条订单行没有可用政策。 */
    private void stubPreview(boolean eligible, String reason) {
        when(orderClient.previewAfterSale(anyString(), anyLong(), anyString(), anyBoolean()))
                .thenReturn(Result.success(AfterSalePreview.builder()
                        .orderItemId(77L).type("RETURN_REFUND")
                        .eligible(eligible).reason(reason)
                        .maxRefundAmount(9900L).itemSubtotal(9900L)
                        .docRef("KB-0003").build()));
    }

    // ==================== 应该命中 ====================

    @Test
    void 售后意图加明确订单引用才命中() {
        assertThat(flow.matches("订单 3 我要退货")).isTrue();
        assertThat(flow.matches("帮我给订单12申请退款")).isTrue();
        assertThat(flow.matches("单号 7 的货我想退掉")).isTrue();
    }

    // ==================== 不该命中（误判防线）====================

    @Test
    void 咨询政策时命中数字不应被当成订单号() {
        // 这是最伤的一类误判：问的是政策，答的却是某个订单的退货步骤
        assertThat(flow.matches("你们退货政策第 3 条是什么")).isFalse();
        assertThat(flow.matches("退货要多久到账，我等了 7 天了")).isFalse();
        assertThat(flow.matches("满 299 减 40 的规则下怎么退货")).isFalse();
    }

    @Test
    void 有意图但没有订单引用不命中() {
        assertThat(flow.matches("我想退货")).isFalse();
        assertThat(flow.matches("七天无理由怎么操作")).isFalse();
        assertThat(flow.matches("")).isFalse();
        assertThat(flow.matches(null)).isFalse();
    }

    @Test
    void 有订单引用但没有售后意图不命中() {
        // 查订单状态是普通查询，不该走售后流程
        assertThat(flow.matches("订单 3 现在什么状态")).isFalse();
        assertThat(flow.matches("帮我看下订单 5 到哪了")).isFalse();
    }

    @Test
    void 订单号提取只在标识词之后生效() {
        assertThat(flow.extractOrderId("订单 12 要退")).isEqualTo(12L);
        assertThat(flow.extractOrderId("单号: 345 退货")).isEqualTo(345L);
        // 数字在前、标识词在后 → 不认
        assertThat(flow.extractOrderId("第 3 条退货政策")).isNull();
        assertThat(flow.extractOrderId("我等了 7 天要退货")).isNull();
    }

    // ==================== 流程已结束 / 尚未付款：不问政策引擎 ====================

    @Test
    void 已取消订单不再引导退货() {
        FlowResult result = flow.execute(contextWithOrder("CANCELLED", "订单 1 我要退货"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("已经是取消状态");
    }

    @Test
    void 已关闭订单不再引导退货() {
        FlowResult result = flow.execute(contextWithOrder("CLOSED", "订单 1 我要退货"));

        assertThat(result.getOutput()).contains("已关闭").contains("重新下单");
    }

    @Test
    void 未支付订单引导取消而不是退货() {
        // 这条是原先错得最实在的一种：订单还没付钱，流程却在教用户怎么退货退款
        FlowResult result = flow.execute(contextWithOrder("CREATED", "订单 1 我要退货"));

        assertThat(result.getOutput()).contains("还没支付").contains("取消订单");
        assertThat(result.getOutput()).doesNotContain("申请退货");
    }

    @Test
    void 退款中的订单不重复引导申请() {
        FlowResult result = flow.execute(contextWithOrder("REFUNDING", "订单 1 我要退款"));

        assertThat(result.getOutput()).contains("退款正在处理中");
    }

    @Test
    void 已退款完成的订单不再引导申请() {
        FlowResult result = flow.execute(contextWithOrder("REFUNDED", "订单 1 我要退款"));

        assertThat(result.getOutput()).contains("已经退款完成");
    }

    // ==================== 未结束的状态：结论必须来自政策引擎 ====================

    @Test
    void 已发货订单按政策引擎的结论回答不能退() {
        // 已发货还没收货 —— 政策引擎会拒，流程要照说，不能自己改口成「可以退」
        stubPreview(false, "订单尚未收货，收货后才能申请售后");
        FlowResult result = flow.execute(
                contextWithOrder("SHIPPED", "订单 1 我要退货", oneItem()));

        assertThat(result.getOutput()).contains("不能申请售后").contains("订单尚未收货");
        assertThat(result.getOutput()).doesNotContain("可以申请售后");
    }

    @Test
    void 可退时报出政策引擎给的可退金额与政策出处() {
        stubPreview(true, null);
        FlowResult result = flow.execute(
                contextWithOrder("RECEIVED", "订单 1 我要退货", oneItem()));

        assertThat(result.getOutput())
                .contains("可以申请售后")
                .contains("深海鱼油软胶囊")
                .contains("99.00")
                .contains("KB-0003");
        // 可退金额与实付相同时不并列两个数字
        assertThat(result.getOutput()).doesNotContain("这件商品实付");
    }

    @Test
    void 换货问的是换货那一套政策() {
        stubPreview(true, null);
        flow.execute(contextWithOrder("RECEIVED", "订单 1 我要换货", oneItem()));

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(orderClient)
                .previewAfterSale(anyString(), anyLong(), type.capture(), anyBoolean());
        // 政策按类型配，退货的政策拿来答换货的问题，报出的数字就是错的
        assertThat(type.getValue()).isEqualTo("EXCHANGE");
    }

    @Test
    void 判定服务不可用时不替用户下结论() {
        when(orderClient.previewAfterSale(anyString(), anyLong(), anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("connection refused"));
        FlowResult result = flow.execute(
                contextWithOrder("RECEIVED", "订单 1 我要退货", oneItem()));

        // 「查不到」不能读成「可以退」，也不能读成「不能退」
        assertThat(result.getOutput()).contains("暂时查不到");
        assertThat(result.getOutput()).doesNotContain("可以申请售后");
    }

    @Test
    void 订单没有明细时不猜判定() {
        FlowResult result = flow.execute(
                contextWithOrder("RECEIVED", "订单 1 我要退货", List.of()));

        assertThat(result.getOutput()).contains("没有商品明细");
        assertThat(result.getOutput()).doesNotContain("可以申请售后");
    }
}
