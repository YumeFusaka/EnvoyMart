package yumefusaka.envoymart.aiservice.flow;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.flow.FlowContext;
import yumefusaka.envoymart.agent.flow.FlowResult;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.model.OrderResponse;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 售后流程的规则判定 —— 重点是<b>误判</b>场景。
 * <p>
 * 规则命中的后果是绕过模型直接回答，误判会让用户拿到答非所问的结果且没有兜底；
 * 漏判还有执行图接着。所以这里的用例大多是"不该命中"的反例。
 */
class AfterSaleFlowTest {

    private final AfterSaleFlow flow = new AfterSaleFlow();

    private FlowContext contextWithOrder(String status, String message) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("order_query").description("查订单")
                        .parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                OrderResponse order = new OrderResponse();
                order.setId(1L);
                order.setOrderNo("YS20260909");
                order.setStatus(status);
                order.setTotalAmount(new BigDecimal("99.00"));
                return ToolResult.builder().success(true).output("订单状态: " + status).rawData(order).build();
            }
        });
        return FlowContext.builder()
                .userId("u1001").sessionId("s1").userMessage(message)
                .toolRegistry(registry).build();
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

    // ==================== 业务判定 ====================

    @Test
    void 未取消订单给出退货步骤() {
        FlowResult result = flow.execute(
                contextWithOrder("DELIVERING", "订单 1 我要退货"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("可以申请退货").contains("YS20260909");
    }

    @Test
    void 已取消订单不再引导退货() {
        FlowResult result = flow.execute(
                contextWithOrder("CANCELLED", "订单 1 我要退货"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("已经是取消状态");
    }
}
