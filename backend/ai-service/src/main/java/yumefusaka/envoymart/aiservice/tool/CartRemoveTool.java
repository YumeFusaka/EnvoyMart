package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.model.AgentCartItem;

import java.util.List;
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
                .parameters(Map.of("cartItemId", ToolDefinition.ParameterSpec.builder()
                        .type("integer").description("购物车行 ID，来自 cart_query").required(true).build()))
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String userId = call.requireUserId();
            long itemId = positiveLong(call.getArguments().get("cartItemId"));
            Downstream.mutate("购物车", () -> orderClient.removeCartItem(userId, itemId));
            List<AgentCartItem> items = Downstream.read("订单服务", () -> orderClient.listCart(userId));
            if (items == null || items.stream().anyMatch(item -> item != null && Long.valueOf(itemId).equals(item.getId()))) {
                throw new IllegalStateException("购物车删除结果与权威状态不一致，请重新查询");
            }
            return ToolResult.builder().success(true)
                    .output("购物车条目 " + itemId + " 已删除。")
                    .rawData(Map.of("cartItemId", itemId, "removed", true))
                    .facts(Map.of("购物车条目", String.valueOf(itemId), "删除状态", "已删除"))
                    .build();
        } catch (Exception e) {
            return Downstream.failure("购物车删除", e);
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
