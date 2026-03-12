package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;

import java.util.Map;

/**
 * 订单查询工具 —— 对接 order-service 的 Feign 客户端。
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
                .description("查询订单状态和详情。需要用户 ID 和订单 ID。")
                .parameters(Map.of(
                        "userId", ToolDefinition.ParameterSpec.builder()
                                .type("string").description("用户 ID").required(true).build(),
                        "orderId", ToolDefinition.ParameterSpec.builder()
                                .type("integer").description("订单 ID").required(true).build()
                ))
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String userId = (String) call.getArguments().get("userId");
            Long orderId = Long.valueOf(call.getArguments().get("orderId").toString());
            var order = orderClient.getOrder(userId, orderId).getData();
            return ToolResult.builder()
                    .success(true)
                    .output("订单状态: " + order.getStatus() + ", 金额: " + order.getTotalAmount())
                    .rawData(order)
                    .build();
        } catch (Exception e) {
            log.error("[OrderTool] execute failed", e);
            return ToolResult.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }
}
