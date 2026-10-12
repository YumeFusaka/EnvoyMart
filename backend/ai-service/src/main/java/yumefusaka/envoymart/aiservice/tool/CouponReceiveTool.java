package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.PromotionClient;
import yumefusaka.envoymart.aiservice.model.AgentCouponResponse;

import java.util.Map;

/** 领取优惠券，恢复时返回首次领取的权威回执。 */
@Slf4j
public class CouponReceiveTool implements Tool {

    private final PromotionClient promotionClient;

    public CouponReceiveTool(PromotionClient promotionClient) {
        this.promotionClient = promotionClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("coupon_receive")
                .description("领取指定优惠券。couponId 必须来自公开优惠券查询 API /coupons/available 的结果，"
                        + "或来自用户明确提供的优惠券编号；查询优惠券由 API/页面承载，不能调用工具表外的前置工具。"
                        + "需要用户确认；重复领取只返回已有券，不要自行编造券 ID。")
                .requiresConfirmation(true)
                .acceptsOperationId(true)
                .parameters(Map.of("couponId", ToolDefinition.ParameterSpec.builder()
                        .type("integer").description("优惠券模板 ID，来自可领取优惠券列表").required(true).build()))
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
            long couponId = positiveLong(call.getArguments().get("couponId"));
            var operation = WriteReceipts.context(call, Map.of("couponId", couponId));
            var receipt = WriteReceipts.run(operation, queryOnly, AgentCouponResponse.class,
                    () -> promotionClient.operationReceipt(userId, operation.operationId(), operation.toolName(), operation.parametersHash()),
                    () -> promotionClient.receiveReceipt(userId, operation.operationId(), couponId));
            AgentCouponResponse authoritative = receipt.data();
            WriteReceipts.require(authoritative != null && authoritative.getId() != null && authoritative.getId() > 0
                    && Long.valueOf(couponId).equals(authoritative.getCouponId()) && "UNUSED".equals(authoritative.getStatus()));
            return ToolResult.builder().success(true).operationState(ToolResult.OperationState.COMMITTED).receiptReplayed(receipt.replayed())
                    .output("优惠券已领取：" + safe(authoritative.getName())
                            + (authoritative.getRuleText() == null ? "" : "（" + authoritative.getRuleText() + "）"))
                    .rawData(authoritative)
                    .facts(Map.of("优惠券", safe(authoritative.getName()), "优惠券状态", safe(authoritative.getStatus())))
                    .build();
        } catch (Exception e) {
            return WriteReceipts.failure(e, "优惠券领取");
        }
    }

    private static long positiveLong(Object raw) {
        try {
            long value = Long.parseLong(String.valueOf(raw));
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (Exception e) {
            throw new IllegalArgumentException("couponId 必须是正整数");
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "未命名优惠券" : value;
    }
}
