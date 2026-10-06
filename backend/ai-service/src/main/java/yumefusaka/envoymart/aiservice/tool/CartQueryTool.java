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

/**
 * 购物车查询工具 —— 只读，列出当前用户购物车里的全部条目。
 * <p>
 * <b>为什么需要一个只读工具。</b>在此之前 Agent 能加购、能结算，却答不出「我购物车里有什么」——
 * 用户被引导着走完下单的前半程，回头想核对一下清单，只能自己去前端翻。工具集里
 * 「写的能力」比「读的能力」还全，是能力划分里的一个缺口，不是模型不聪明。
 * <p>
 * 只读、不改状态，因此不需要用户确认（与 cart_add / cart_checkout 不同）。
 */
@Slf4j
public class CartQueryTool implements Tool {

    private final OrderClient orderClient;

    public CartQueryTool(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("cart_query")
                .description("查询当前用户购物车里的全部商品：名称、规格、单价、数量、小计与是否可购买。"
                        + "用户问「我购物车里有什么」「购物车多少钱」「这个加入购物车了吗」时使用。"
                        + "购物车为空时返回空列表，如实告诉用户购物车是空的，不要编造条目。")
                .parameters(Map.of())
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String userId = call.requireUserId();
            List<AgentCartItem> items = Downstream.read("订单服务", () -> orderClient.listCart(userId));
            if (items == null || items.isEmpty()) {
                return ToolResult.builder().success(true)
                        .output("购物车是空的。")
                        .build();
            }

            StringBuilder sb = new StringBuilder("购物车共 ")
                    .append(items.size()).append(" 种商品：\n");
            long total = 0L;
            int unavailable = 0;
            for (AgentCartItem item : items) {
                sb.append("  · ").append(item.getName());
                if (item.getSpecText() != null && !item.getSpecText().isBlank()) {
                    sb.append("（").append(item.getSpecText()).append("）");
                }
                sb.append(" × ").append(item.getQuantity())
                        .append(" = ").append(Money.yuan(item.getSubtotal())).append("\n");
                if (item.getSubtotal() != null) {
                    total += item.getSubtotal();
                }
                if (Boolean.FALSE.equals(item.getAvailable())) {
                    unavailable++;
                    sb.append("    注意：该规格当前不可购买（已下架或售罄）\n");
                }
            }
            sb.append("合计：").append(Money.yuan(total));
            if (unavailable > 0) {
                sb.append("\n其中 ").append(unavailable).append(" 种当前不可购买，结算前需先移除或更换。");
            }

            return ToolResult.builder()
                    .success(true)
                    .output(sb.toString())
                    .rawData(items)
                    .build();
        } catch (Exception e) {
            return Downstream.failure("购物车查询", e);
        }
    }
}