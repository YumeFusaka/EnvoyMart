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
                    // 写「订单状态」而不是「状态」：模型复述输出，标签跟着一起过去，
                    // 事实核对才找得到锚点（「状态」会撞上「物流状态」）
                    .append("，订单状态：").append(text(order.getStatusText(), order.getStatus())).append("\n");
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
                    .facts(factsOf(order))
                    .build();
        } catch (Exception e) {
            log.error("[OrderTool] execute failed", e);
            return ToolResult.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }

    /**
     * 这次查询确立的两条事实，交给 {@code ToolFactVerifier} 拿回答逐条核对。
     * <p>
     * <b>标签挑的是「只可能指这一个字段」的说法。</b>写「状态」会撞上「物流状态」
     * 「支付状态」——校验器看到标签就认为模型在下断言，撞一次就把一句正确的话判成矛盾。
     * 写「订单状态」就只可能是这一单的状态。
     * <p>
     * 标签必须与模型答话时用的词对得上，否则核对器永远不触发、看着像通过。
     * 所以这里挑的是最自然的书面说法（也就是 {@link #getDefinition()} 的描述里用的词），
     * 而不是输出行里的简写。
     */
    private Map<String, String> factsOf(OrderResponse order) {
        return Map.of(
                "订单状态", text(order.getStatusText(), order.getStatus()),
                "应付金额", Money.yuan(order.getPayAmount()));
    }

    private String text(String statusText, String status) {
        return statusText != null && !statusText.isBlank() ? statusText : status;
    }
}
