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
                .description("取消未支付的订单。不可撤销，需要用户确认。")
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
            Long orderId = Long.valueOf(call.getArguments().get("orderId").toString());
            var order = orderClient.cancelOrder(userId, orderId).getData();
            return ToolResult.builder()
                    .success(true)
                    .output("订单 " + order.getOrderNo() + " 已取消，库存已回补。")
                    .rawData(order)
                    .build();
        } catch (Exception e) {
            log.error("[CancelOrderTool] execute failed", e);
            return ToolResult.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }
}
