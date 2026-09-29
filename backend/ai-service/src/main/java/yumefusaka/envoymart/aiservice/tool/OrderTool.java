package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.contract.OrderItemResponse;
import yumefusaka.envoymart.contract.OrderResponse;

import java.util.List;
import java.util.Map;

/**
 * 订单查询工具 —— 对接 order-service 的 Feign 客户端。
 * <p>
 * 输出里的状态用 {@link OrderResponse#getStatusText()}（「已支付」）而不是
 * {@link OrderResponse#getStatus()}（{@code PAID}）。模型会<b>原样复述</b>工具给它的字符串，
 * 给枚举名，用户就会看到「您的订单状态是 PAID」。
 */
@Slf4j
public class OrderTool implements Tool {

    private final OrderClient orderClient;

    public OrderTool(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("order_query")
                .description("按订单编号查询当前用户的订单详情：状态、金额、商品明细、收货信息。"
                        + "订单编号是用户在订单列表看到的数字 id。只能查自己的订单。")
                .parameters(Map.of(
                        "orderId", ToolDefinition.ParameterSpec.builder()
                                .type("integer").description("订单编号").required(true).build()
                ))
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String userId = call.requireUserId();
            Long orderId = Long.valueOf(String.valueOf(call.getArguments().get("orderId")));
            OrderResponse order = orderClient.getOrder(userId, orderId).getData();

            if (order == null) {
                return ToolResult.builder().success(true)
                        .output("没有找到订单 " + orderId + "。请确认订单编号是否正确。")
                        .build();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("订单 ").append(order.getOrderNo())
                    .append("，状态：").append(text(order.getStatusText(), order.getStatus())).append("\n");
            sb.append("应付金额：").append(Money.yuan(order.getPayAmount())).append("\n");

            List<OrderItemResponse> items = order.getItems();
            if (items != null && !items.isEmpty()) {
                sb.append("商品明细：\n");
                for (OrderItemResponse item : items) {
                    sb.append("  - ").append(item.getSpuName());
                    if (item.getSkuSpecText() != null && !item.getSkuSpecText().isBlank()) {
                        sb.append("（").append(item.getSkuSpecText()).append("）");
                    }
                    sb.append(" ×").append(item.getQuantity())
                            .append("，单价 ").append(Money.yuan(item.getUnitPrice())).append("\n");
                }
            }

            if (order.getExpireAt() != null && "CREATED".equals(order.getStatus())) {
                sb.append("支付截止：").append(order.getExpireAt()).append("\n");
            }
            if (order.getCancelReason() != null && !order.getCancelReason().isBlank()) {
                sb.append("取消原因：").append(order.getCancelReason()).append("\n");
            }

            return ToolResult.builder()
                    .success(true)
                    .output(sb.toString())
                    .rawData(order)
                    .build();
        } catch (Exception e) {
            log.error("[OrderTool] execute failed", e);
            return ToolResult.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }

    private String text(String statusText, String status) {
        return statusText != null && !statusText.isBlank() ? statusText : status;
    }
}
