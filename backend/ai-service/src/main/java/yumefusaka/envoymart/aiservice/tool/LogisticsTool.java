package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.contract.LogisticsResponse;
import yumefusaka.envoymart.contract.LogisticsStepResponse;

import java.util.List;
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
                .description("按订单编号查询当前用户的物流轨迹：承运商、运单号、每一步的状态、时间与所在地。"
                        + "用户问「货到哪了」「什么时候能到」时使用。"
                        + "轨迹不含预计到达时间——承运商没有给出这个信息时，不要替它估一个。")
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
            LogisticsResponse logistics =
                    Downstream.read("订单服务", () -> orderClient.getLogistics(userId, orderId));

            // 走到这里 null 只可能是「订单里没有这条物流」——下游没做成的那种已经被
            // Downstream 拦成异常了，不会被当成「没有物流」说出来
            if (logistics == null) {
                return ToolResult.builder().success(true)
                        .output("没有找到订单 " + orderId + " 的物流信息。")
                        .build();
            }

            // steps 契约上是空列表而非 null，但跨进程的「契约」只由测试保证，
            // 一次运行期判空比一次 NPE → success=false → 模型自行发挥便宜得多
            List<LogisticsStepResponse> steps = logistics.getSteps();
            if (steps == null || steps.isEmpty()) {
                return ToolResult.builder().success(true)
                        .output("订单 " + logistics.getOrderNo() + " 还没有物流轨迹，可能尚未发货。")
                        .rawData(logistics)
                        .build();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("订单 ").append(logistics.getOrderNo());
            if (logistics.getCarrier() != null) {
                sb.append("，承运商：").append(logistics.getCarrier());
            }
            if (logistics.getTrackingNo() != null) {
                sb.append("，运单号：").append(logistics.getTrackingNo());
            }
            sb.append("\n物流轨迹：\n");
            for (LogisticsStepResponse step : steps) {
                sb.append("  ").append(step.getTime()).append(" ")
                        .append(step.getStatus());
                if (step.getDetail() != null && !step.getDetail().isBlank()) {
                    sb.append(" —— ").append(step.getDetail());
                }
                // 地点单独给：用户问的是「货到哪了」，而"到哪了"问的就是地点。
                // 只给状态不给地点，模型只能答"已发往下一站"——等于没答
                if (step.getLocation() != null && !step.getLocation().isBlank()) {
                    sb.append("（").append(step.getLocation()).append("）");
                }
                sb.append("\n");
            }

            return ToolResult.builder()
                    .success(true)
                    .output(sb.toString())
                    .rawData(logistics)
                    .build();
        } catch (Exception e) {
            return Downstream.failure("物流查询", e);
        }
    }
}
