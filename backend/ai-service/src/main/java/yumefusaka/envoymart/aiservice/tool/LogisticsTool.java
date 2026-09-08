package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;

import java.util.Map;

/**
 * 物流查询工具 —— 调用 order-service 获取物流轨迹。
 */
@Slf4j
public class LogisticsTool implements Tool {

    private final OrderClient orderClient;

    public LogisticsTool(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("logistics_query")
                .description("查询当前用户的订单物流信息。需要订单 ID。")
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
            var logistics = orderClient.getLogistics(userId, orderId).getData();
            var steps = logistics.getSteps();
            var latestStep = steps.isEmpty() ? null : steps.get(steps.size() - 1);
            String status = latestStep == null ? "暂无物流信息" : latestStep.getStatus();
            String latest = latestStep == null ? "暂无物流信息" : latestStep.getDetail();
            return ToolResult.builder()
                    .success(true)
                    .output("物流状态: " + status + ", 最新: " + latest)
                    .rawData(logistics)
                    .build();
        } catch (Exception e) {
            log.error("[LogisticsTool] execute failed", e);
            return ToolResult.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }
}
