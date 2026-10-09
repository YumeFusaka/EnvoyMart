package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.PromotionClient;
import yumefusaka.envoymart.aiservice.model.AgentCouponResponse;

import java.util.List;
import java.util.Map;

/** 领取优惠券；写后从用户券包重新读取确认领取结果。 */
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
                .description("领取指定优惠券。couponId 必须来自 coupon_available 的结果，需要用户确认；"
                        + "重复领取只返回已有券，不要自行编造券 ID。")
                .requiresConfirmation(true)
                .parameters(Map.of("couponId", ToolDefinition.ParameterSpec.builder()
                        .type("integer").description("优惠券模板 ID，来自可领取优惠券列表").required(true).build()))
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String userId = call.requireUserId();
            long couponId = positiveLong(call.getArguments().get("couponId"));
            AgentCouponResponse receipt = Downstream.mutate("优惠券", () -> promotionClient.receive(userId, couponId));
            List<AgentCouponResponse> mine = Downstream.read("优惠券", () -> promotionClient.mine(userId, "UNUSED"));
            AgentCouponResponse authoritative = mine == null ? receipt : mine.stream()
                    .filter(coupon -> coupon != null && (Long.valueOf(couponId).equals(coupon.getCouponId())
                            || Long.valueOf(couponId).equals(coupon.getId())))
                    .findFirst().orElse(receipt);
            if (authoritative == null) {
                throw new IllegalStateException("领券结果无法从用户券包复核，请重新查询");
            }
            return ToolResult.builder().success(true)
                    .output("优惠券已领取：" + safe(authoritative.getName())
                            + (authoritative.getRuleText() == null ? "" : "（" + authoritative.getRuleText() + "）"))
                    .rawData(authoritative)
                    .facts(Map.of("优惠券", safe(authoritative.getName()), "优惠券状态", safe(authoritative.getStatus())))
                    .build();
        } catch (Exception e) {
            return Downstream.failure("优惠券领取", e);
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
