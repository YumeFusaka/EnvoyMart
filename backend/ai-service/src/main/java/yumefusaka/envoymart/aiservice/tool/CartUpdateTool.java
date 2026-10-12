package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.model.AgentCartItem;
import yumefusaka.envoymart.aiservice.model.AgentUpdateCartRequest;

import java.util.Map;

/** 修改购物车行数量，返回同一事务保存的首次回执。 */
@Slf4j
public class CartUpdateTool implements Tool {

    private final OrderClient orderClient;

    public CartUpdateTool(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("cart_update")
                .description("把指定购物车行改成目标数量。必须使用 cart_query 返回的购物车行 id，"
                        + "不能用 SKU 或商品名代替；数量必须是 1 到 99 的整数，需要用户确认。")
                .requiresConfirmation(true)
                .acceptsOperationId(true)
                .parameters(Map.of(
                        "cartItemId", ToolDefinition.ParameterSpec.builder()
                                .type("integer").description("购物车行 ID，来自 cart_query").required(true).build(),
                        "quantity", ToolDefinition.ParameterSpec.builder()
                                .type("integer").description("目标数量，1 到 99").required(true).build()))
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
            long itemId = positiveLong(call.getArguments().get("cartItemId"), "cartItemId");
            int quantity = boundedQuantity(call.getArguments().get("quantity"));
            AgentUpdateCartRequest request = new AgentUpdateCartRequest();
            request.setQuantity(quantity);
            var operation = WriteReceipts.context(call, Map.of("cartItemId", itemId, "quantity", quantity));
            var receipt = WriteReceipts.run(operation, queryOnly, AgentCartItem.class,
                    () -> orderClient.operationReceipt(userId, operation.operationId(), operation.toolName(), operation.parametersHash()),
                    () -> orderClient.updateCartItemReceipt(userId, operation.operationId(), itemId, request));
            AgentCartItem authoritative = receipt.data();
            WriteReceipts.require(authoritative != null && Long.valueOf(itemId).equals(authoritative.getId())
                    && Integer.valueOf(quantity).equals(authoritative.getQuantity()) && authoritative.getSkuId() != null
                    && authoritative.getSubtotal() != null && authoritative.getSubtotal() >= 0);
            return ToolResult.builder().success(true).operationState(ToolResult.OperationState.COMMITTED).receiptReplayed(receipt.replayed())
                    .output("购物车已更新：" + label(authoritative) + " × " + authoritative.getQuantity()
                            + "，小计 " + Money.yuan(authoritative.getSubtotal()) + "。")
                    .rawData(authoritative)
                    .facts(Map.of("购物车数量", String.valueOf(authoritative.getQuantity()),
                            "购物车 SKU", String.valueOf(authoritative.getSkuId())))
                    .build();
        } catch (Exception e) {
            return WriteReceipts.failure(e, "购物车修改");
        }
    }

    private static long positiveLong(Object raw, String field) {
        try {
            long value = Long.parseLong(String.valueOf(raw));
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " 必须是正整数");
        }
    }

    private static int boundedQuantity(Object raw) {
        try {
            int value = Integer.parseInt(String.valueOf(raw));
            if (value < 1 || value > 99) throw new NumberFormatException();
            return value;
        } catch (Exception e) {
            throw new IllegalArgumentException("quantity 必须是 1 到 99 的整数");
        }
    }

    private static String label(AgentCartItem item) {
        return item.getName() + (item.getSpecText() == null ? "" : "（" + item.getSpecText() + "）");
    }
}
