package yumefusaka.envoymart.orderservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.orderservice.entity.AfterSalePolicyEntity;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;
import yumefusaka.envoymart.orderservice.entity.OrderItemEntity;
import yumefusaka.envoymart.orderservice.mapper.AfterSalePolicyMapper;
import yumefusaka.envoymart.orderservice.model.OrderStatus;
import yumefusaka.envoymart.orderservice.model.PolicyDecision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * 售后政策引擎。
 * <p>
 * <b>为什么是规则而不是让模型判断</b>：判定涉及时间窗与金额 ——
 * 「收货第 8 天还能不能无理由退」「拆封的商品该退多少」。交给模型即兴推理，
 * 它可能给出一个听起来合理但并不存在的期限，而这里的错误直接对应资损。
 * <p>
 * 规则只负责给结论；「为什么」由 {@code docRef} 指向知识库里的政策原文，
 * 由检索链路给出引用。
 */
@Slf4j
@Component
public class AfterSalePolicyEngine {

    /** 没配政策时的兜底值。**宁可宽松也不能拒绝** —— 拒绝的是用户本就该有的权利 */
    private static final int FALLBACK_RETURN_DAYS = 7;
    private static final int FALLBACK_QUALITY_DAYS = 15;

    /** 可以申请售后的订单状态：收到货之后 */
    private static final Set<OrderStatus> AFTER_SALE_ELIGIBLE =
            Set.of(OrderStatus.RECEIVED, OrderStatus.COMPLETED);

    public static final String TYPE_REFUND_ONLY = "REFUND_ONLY";
    public static final String TYPE_RETURN_REFUND = "RETURN_REFUND";
    public static final String TYPE_EXCHANGE = "EXCHANGE";

    private final AfterSalePolicyMapper policyMapper;

    public AfterSalePolicyEngine(AfterSalePolicyMapper policyMapper) {
        this.policyMapper = policyMapper;
    }

    /**
     * 判定某一订单行能不能退、最多退多少。
     *
     * @param qualityIssue 是否属于质量问题。质量问题的期限比无理由退货长
     */
    public PolicyDecision evaluate(OrderEntity order, OrderItemEntity item,
                                   String type, boolean qualityIssue) {
        OrderStatus status = OrderStatus.parse(order.getStatus());
        if (!AFTER_SALE_ELIGIBLE.contains(status)) {
            return PolicyDecision.reject("订单尚未收货，收货后才能申请售后", null);
        }

        LocalDateTime receivedAt = order.getReceivedAt();
        if (receivedAt == null) {
            // 状态是已收货却没有收货时间 —— 数据不自洽时拒绝而不是猜一个时间，
            // 猜出来的期限可能把用户挡在门外
            log.warn("[AfterSale] 订单已收货但缺少收货时间 orderId={}", order.getId());
            return PolicyDecision.reject("订单缺少收货时间，无法判定售后时效", null);
        }

        AfterSalePolicyEntity policy = resolvePolicy(item.getCategoryId(), type);
        int days = policy == null || policy.getReturnDays() == null
                ? FALLBACK_RETURN_DAYS : policy.getReturnDays();
        int qualityDays = policy == null || policy.getQualityDays() == null
                ? FALLBACK_QUALITY_DAYS : policy.getQualityDays();

        long elapsed = ChronoUnit.DAYS.between(receivedAt.toLocalDate(), Times.now().toLocalDate());
        int limit = qualityIssue ? qualityDays : days;
        if (elapsed > limit) {
            String reason = qualityIssue
                    ? "已超过质量问题 " + limit + " 天的处理期限（已过 " + elapsed + " 天）"
                    : "已超过 " + limit + " 天无理由退货期限（已过 " + elapsed + " 天）";
            return PolicyDecision.reject(reason, policy == null ? null : policy.getDocRef());
        }

        if (policy != null && policy.getReturnable() != null && policy.getReturnable() == 0) {
            return PolicyDecision.reject(
                    policy.getRequirements() == null ? "该商品不支持退货" : policy.getRequirements(),
                    policy.getDocRef());
        }

        BigDecimal ratio = policy == null || policy.getMaxRefundRatio() == null
                ? BigDecimal.ONE : policy.getMaxRefundRatio();
        // 向下取整到分：宁可少退一分，也不能因为四舍五入而多退
        long maxRefund = BigDecimal.valueOf(item.getSubtotal() == null ? 0L : item.getSubtotal())
                .multiply(ratio)
                .setScale(0, RoundingMode.DOWN)
                .longValue();

        return PolicyDecision.allow(maxRefund,
                policy == null ? null : policy.getDocRef(),
                policy == null ? null : policy.getRequirements(),
                policy == null ? null : policy.getId());
    }

    /**
     * 取适用的政策。
     * <p>
     * 优先级：类目 + 类型精确匹配 → 全类目 + 类型默认。找不到就返回 null，
     * 由调用方用兜底值 —— 政策表为空不该让售后整个不可用。
     */
    private AfterSalePolicyEntity resolvePolicy(Long categoryId, String type) {
        if (categoryId != null) {
            AfterSalePolicyEntity exact = policyMapper.selectOne(
                    new LambdaQueryWrapper<AfterSalePolicyEntity>()
                            .eq(AfterSalePolicyEntity::getCategoryId, categoryId)
                            .eq(AfterSalePolicyEntity::getType, type)
                            .last("limit 1"));
            if (exact != null) {
                return exact;
            }
        }
        return policyMapper.selectOne(new LambdaQueryWrapper<AfterSalePolicyEntity>()
                .isNull(AfterSalePolicyEntity::getCategoryId)
                .eq(AfterSalePolicyEntity::getType, type)
                .last("limit 1"));
    }
}
