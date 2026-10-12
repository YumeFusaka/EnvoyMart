package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;

import java.util.Map;

/** 删除购物车行；删除完成后通过权威列表确认条目确实消失。 */
@Slf4j
public class CartRemoveTool implements Tool {

    private final OrderClient orderClient;

    public CartRemoveTool(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("cart_remove")
                .description("删除指定购物车行。必须使用 cart_query 返回的购物车行 id，不能用 SKU 或商品名，需要用户确认。")
                .requiresConfirmation(true)
                .acceptsOperationId(true)
                .parameters(Map.of("cartItemId", ToolDefinition.ParameterSpec.builder()
                        .type("integer").description("购物车行 ID，来自 cart_query").required(true).build()))
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
            long itemId = positiveLong(call.getArguments().get("cartItemId"));
            var operation = WriteReceipts.context(call, Map.of("cartItemId", itemId));
            var receipt = WriteReceipts.run(operation, queryOnly, Void.class,
                    () -> orderClient.operationReceipt(userId, operation.operationId(), operation.toolName(), operation.parametersHash()),
                    () -> orderClient.removeCartItemReceipt(userId, operation.operationId(), itemId));
            return ToolResult.builder().success(true).operationState(ToolResult.OperationState.COMMITTED).receiptReplayed(receipt.replayed())
                    .output("购物车条目 " + itemId + " 已删除。")
                    .rawData(Map.of("cartItemId", itemId, "removed", true))
                    .facts(Map.of("购物车条目", String.valueOf(itemId), "删除状态", "已删除"))
                    .build();
        } catch (Exception e) {
            return WriteReceipts.failure(e, "购物车删除");
        }
    }

    private static long positiveLong(Object raw) {
        try {
            long value = Long.parseLong(String.valueOf(raw));
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (Exception e) {
            throw new IllegalArgumentException("cartItemId 必须是正整数");
        }
    }
}
