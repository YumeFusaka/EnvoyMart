package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;

import java.util.Map;

/**
 * 取消订单工具 —— 不可撤销的高危操作。
 * <p>
 * 标记 requiresConfirmation 后，模型无法自主执行，必须由用户显式确认。
 */
@Slf4j
public class CancelOrderTool implements Tool {

    private final OrderClient orderClient;

    public CancelOrderTool(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("order_cancel")
                .description("取消未支付的订单。不可撤销，需要用户确认。"
                        + "orderId 可以是订单查询结果里的数字 id，也兼容用户看到的 YS 开头订单号。")
                .requiresConfirmation(true)
                .parameters(Map.of(
                        "orderId", ToolDefinition.ParameterSpec.builder()
                                .type("integer").description("订单 ID").required(true).build()
                ))
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String userId = call.requireUserId();
            Object rawOrderId = call.getArguments().get("orderId");
            if (rawOrderId == null || rawOrderId.toString().isBlank()) {
                throw new IllegalArgumentException("缺少订单 ID 或订单号，请先查询订单");
            }
            // 写操作：下游说「没做成」时绝不重发——应答丢了不代表业务没生效，
            // 重发就是第二次取消。见 Downstream#mutate
            String raw = rawOrderId.toString().trim();
            var order = raw.matches("\\d+")
                    ? Downstream.mutate("订单服务", () -> orderClient.cancelOrder(userId, Long.valueOf(raw)))
                    : Downstream.mutate("订单服务", () -> orderClient.cancelOrderByNo(userId, raw));
            return ToolResult.builder()
                    .success(true)
                    .output("订单 " + order.getOrderNo() + " 已取消，库存已回补。")
                    .rawData(order)
                    .build();
        } catch (Exception e) {
            // 「订单已取消，不能重复取消」这类拒绝原先会变成一句 NPE（order 为 null），
            // 现在原样是下游给用户的那句话
            return Downstream.failure("订单取消", e);
        }
    }
}
