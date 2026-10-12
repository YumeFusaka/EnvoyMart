package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.model.AgentAfterSaleRequest;
import yumefusaka.envoymart.aiservice.model.AgentAfterSaleResult;

import java.util.Map;
import java.util.Set;

/**
 * 提交售后申请工具 —— 用户说「我要退货」时真正把申请交上去。
 * <p>
 * 售后确定性流程通过已有预览 API 提供资格与金额；本工具在用户确认后提交。
 * 恢复先读取同一操作的持久回执；首次提交由交易域统一判定资格，
 * 不能用后来的在途申请状态拒绝一条此前已成功的回执。
 */
@Slf4j
public class AfterSaleTool implements Tool {

    /** 交易域认的三个类型。列在这里是为了让「说明里写得出、代码里查得到」是同一份 */
    private static final Set<String> TYPES = Set.of("REFUND_ONLY", "RETURN_REFUND", "EXCHANGE");

    private final OrderClient orderClient;

    public AfterSaleTool(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("after_sale_apply")
                .description("为某个订单行提交售后申请（退款 / 退货 / 换货）。"
                        + "用户说「我要退货」「申请退款」「这个坏了要换」时使用。"
                        + "售后确定性流程和订单页面通过资格预览 API 查询条件；"
                        + "本工具会在执行时预检同一订单行，不符合时不提交并返回原因。"
                        + "orderItemId 是订单条目编号（订单详情里每一行一个），不是订单编号。")
                .requiresConfirmation(true)
                .acceptsOperationId(true)
                .parameters(Map.of(
                        "orderItemId", ToolDefinition.ParameterSpec.builder()
                                .type("integer")
                                .description("订单条目编号（订单详情里每一行对应一个 orderItemId）")
                                .required(true).build(),
                        "type", ToolDefinition.ParameterSpec.builder()
                                .type("string")
                                .description("售后类型，只能取交易接口三个值之一：REFUND_ONLY（仅退款）、"
                                        + "RETURN_REFUND（退货退款）、EXCHANGE（换货）。"
                                        + "用户说「退了不要了」取 RETURN_REFUND，说「只退钱」取 REFUND_ONLY，"
                                        + "说「换一个」取 EXCHANGE")
                                .required(true).build(),
                        "reason", ToolDefinition.ParameterSpec.builder()
                                .type("string")
                                .description("必填申请原因，1 至 64 字，例如「不想要了」「收到时已破损」")
                                .required(true).build(),
                        "qualityIssue", ToolDefinition.ParameterSpec.builder()
                                .type("boolean")
                                .description("是否属于质量问题。破损、变质、与描述不符取 true；"
                                        + "单纯不想要取 false。不传按 false 处理")
                                .required(false).build()
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
            AgentAfterSaleRequest request = new AgentAfterSaleRequest();
            Object rawId = call.getArguments().get("orderItemId");
            if (rawId == null || !String.valueOf(rawId).matches("[1-9]\\d*")) {
                throw new IllegalArgumentException("orderItemId 必须是正整数订单条目编号");
            }
            request.setOrderItemId(Long.valueOf(String.valueOf(rawId)));
            request.setType(normalizeType(call));
            Object reason = call.getArguments().get("reason");
            String reasonText = reason == null ? "" : String.valueOf(reason).strip();
            if (reasonText.isEmpty() || reasonText.length() > 64) {
                throw new IllegalArgumentException("申请原因 reason 必填，长度为 1 至 64 字");
            }
            request.setReason(reasonText);
            request.setQualityIssue(qualityIssueOf(call));

            Map<String, Object> parameters = new java.util.LinkedHashMap<>();
            parameters.put("orderItemId", request.getOrderItemId()); parameters.put("type", request.getType());
            parameters.put("reason", request.getReason()); parameters.put("description", request.getDescription());
            parameters.put("qualityIssue", Boolean.TRUE.equals(request.getQualityIssue()));
            var operation = WriteReceipts.context(call, parameters);
            var receipt = WriteReceipts.run(operation, queryOnly, AgentAfterSaleResult.class,
                    () -> orderClient.operationReceipt(userId, operation.operationId(), operation.toolName(), operation.parametersHash()),
                    () -> orderClient.applyAfterSaleReceipt(userId, operation.operationId(), request));
            AgentAfterSaleResult result = receipt.data();
            WriteReceipts.require(result != null && result.getId() != null && result.getId() > 0
                    && result.getAfterSaleNo() != null && !result.getAfterSaleNo().isBlank()
                    && result.getStatusText() != null && !result.getStatusText().isBlank());
            log.info("[售后工具] 阶段=提交 itemId={} type={} afterSaleId={} statusText={}",
                    request.getOrderItemId(), request.getType(), result.getId(), result.getStatusText());
            StringBuilder sb = new StringBuilder("售后申请已提交，单号 ").append(result.getAfterSaleNo());
            if (result.getStatusText() != null) {
                sb.append("，当前状态：").append(result.getStatusText());
            }
            if (result.getRefundAmount() != null) {
                sb.append("，预计退款 ").append(Money.yuan(result.getRefundAmount()));
            }
            if (result.getDocRef() != null) {
                sb.append("。判据见 ").append(result.getDocRef());
            }
            return ToolResult.builder()
                    .success(true).operationState(ToolResult.OperationState.COMMITTED).receiptReplayed(receipt.replayed())
                    .output(sb.toString()).rawData(result).build();
        } catch (Exception e) {
            return WriteReceipts.failure(e, "售后申请");
        }
    }

    /**
     * 类型统一成大写再比对白名单。
     * <p>
     * 模型偶尔会把 {@code RETURN_REFUND} 写成小写，大小写不该成为一次申请失败的
     * 原因；但**白名单本身要拦**——编出来的类型必须当场失败，
     * 否则它会一路走到下游、回来的错误信息里没有「你写错了类型」这层意思。
     */
    private String normalizeType(ToolCall call) {
        Object raw = call.getArguments().get("type");
        String type = raw == null ? "" : String.valueOf(raw).strip().toUpperCase(java.util.Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("售后类型只能是 REFUND_ONLY / RETURN_REFUND / EXCHANGE，收到的是「" + raw + "」");
        }
        return type;
    }

    private boolean qualityIssueOf(ToolCall call) {
        Object raw = call.getArguments().get("qualityIssue");
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        String value = String.valueOf(raw).strip();
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("qualityIssue 只能为 true 或 false");
        }
        return Boolean.parseBoolean(value);
    }
}
