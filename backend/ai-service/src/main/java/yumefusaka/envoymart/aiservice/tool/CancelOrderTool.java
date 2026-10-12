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
                .acceptsOperationId(true)
                .parameters(Map.of(
                        "orderId", ToolDefinition.ParameterSpec.builder()
                                .type("integer").description("订单 ID").required(true).build()
                ))
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        return execute(call, false);
    }
    @Override public ToolResult recover(ToolCall call) { return execute(call, true); }
    private ToolResult execute(ToolCall call, boolean queryOnly) {
        try {
            String userId = call.requireUserId();
            Object rawOrderId = call.getArguments().get("orderId");
            if (rawOrderId == null || rawOrderId.toString().isBlank()) {
                throw new IllegalArgumentException("缺少订单 ID 或订单号，请先查询订单");
            }
            // 用户给定的编号形态参与参数指纹，恢复不能悄悄改变目标。
            String raw = rawOrderId.toString().trim();
            String normalized = raw.matches("\\d+") ? String.valueOf(Long.valueOf(raw)) : raw;
            var operation = WriteReceipts.context(call, Map.of("orderId", normalized));
            var receipt = WriteReceipts.run(operation, queryOnly, yumefusaka.envoymart.contract.OrderResponse.class,
                    () -> orderClient.operationReceipt(userId, operation.operationId(), operation.toolName(), operation.parametersHash()),
                    () -> raw.matches("\\d+") ? orderClient.cancelOrderReceipt(userId, operation.operationId(), Long.valueOf(raw))
                            : orderClient.cancelOrderByNoReceipt(userId, operation.operationId(), raw));
            var order = receipt.data();
            WriteReceipts.require(order != null && order.getId() != null && order.getId() > 0
                    && order.getOrderNo() != null && (raw.matches("\\d+") ? Long.valueOf(raw).equals(order.getId()) : raw.equals(order.getOrderNo()))
                    && order.getStatus() != null && java.util.Set.of("CANCELLED", "REFUNDING", "REFUNDED").contains(order.getStatus()));
            return ToolResult.builder()
                    .success(true)
                    .operationState(ToolResult.OperationState.COMMITTED).receiptReplayed(receipt.replayed())
                    .output("订单 " + order.getOrderNo() + " 取消请求已处理，当前状态：" + order.getStatus() + "。")
                    .rawData(order)
                    .build();
        } catch (Exception e) {
            // 「订单已取消，不能重复取消」这类拒绝原先会变成一句 NPE（order 为 null），
            // 现在原样是下游给用户的那句话
            return WriteReceipts.failure(e, "订单取消");
        }
    }
}
