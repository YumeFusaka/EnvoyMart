package yumefusaka.envoymart.promotionservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.promotionservice.entity.CouponEntity;
import yumefusaka.envoymart.promotionservice.entity.UserCouponEntity;
import yumefusaka.envoymart.promotionservice.mapper.CouponMapper;
import yumefusaka.envoymart.promotionservice.mapper.UserCouponMapper;
import yumefusaka.envoymart.promotionservice.model.CouponResponse;
import yumefusaka.envoymart.promotionservice.model.UserCouponResponse;
import yumefusaka.envoymart.promotionservice.service.CouponService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CouponServiceImpl implements CouponService {

    private static final String STATUS_UNUSED = "UNUSED";
    private static final String STATUS_USED = "USED";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String TYPE_FIXED = "FIXED";
    private static final String TYPE_DISCOUNT = "DISCOUNT";
    private static final int COUPON_ENABLED = 1;

    /** 领取后默认有效期。模板没配 validDays 也没配绝对时间时用它 */
    private static final int DEFAULT_VALID_DAYS = 30;

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;

    public CouponServiceImpl(CouponMapper couponMapper, UserCouponMapper userCouponMapper) {
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
    }

    @Override
    public List<CouponResponse> availableCoupons(String userId) {
        LocalDateTime now = Times.now();
        List<CouponEntity> coupons = couponMapper.selectList(new LambdaQueryWrapper<CouponEntity>()
                .eq(CouponEntity::getStatus, COUPON_ENABLED)
                // 只列「现在就能领」的：还没开始的券列出来只会让人点一下发现领不了
                .and(w -> w.isNull(CouponEntity::getStartTime).or().le(CouponEntity::getStartTime, now))
                .and(w -> w.isNull(CouponEntity::getEndTime).or().ge(CouponEntity::getEndTime, now))
                .orderByDesc(CouponEntity::getId));
        if (coupons.isEmpty()) {
            return List.of();
        }

        // 一次查出已领过的模板 id，而不是逐张查一次
        Set<Long> receivedIds = userCouponMapper.selectList(
                        new LambdaQueryWrapper<UserCouponEntity>().eq(UserCouponEntity::getUserId, userId))
                .stream()
                .map(UserCouponEntity::getCouponId)
                .collect(Collectors.toSet());

        return coupons.stream()
                .map(coupon -> toCouponResponse(coupon, receivedIds.contains(coupon.getId())))
                .toList();
    }

    @Override
    @Transactional
    public UserCouponResponse receive(String userId, Long couponId) {
        CouponEntity coupon = couponId == null ? null : couponMapper.selectById(couponId);
        if (coupon == null || coupon.getStatus() == null || coupon.getStatus() != COUPON_ENABLED) {
            throw new IllegalArgumentException("优惠券不存在或已下架");
        }

        LocalDateTime now = Times.now();
        if (coupon.getStartTime() != null && coupon.getStartTime().isAfter(now)) {
            throw new IllegalStateException("该优惠券尚未开始发放");
        }
        if (coupon.getEndTime() != null && coupon.getEndTime().isBefore(now)) {
            throw new IllegalStateException("该优惠券已过期");
        }

        Long already = userCouponMapper.selectCount(new LambdaQueryWrapper<UserCouponEntity>()
                .eq(UserCouponEntity::getUserId, userId)
                .eq(UserCouponEntity::getCouponId, couponId));
        if (already != null && already > 0) {
            throw new IllegalStateException("您已领取过该优惠券");
        }

        // 条件更新扣发行量：判断与递增在同一条语句里，超发由数据库兜住
        if (couponMapper.incrementReceived(couponId) == 0) {
            throw new IllegalStateException("该优惠券已被领完");
        }

        LocalDateTime expireAt = coupon.getValidDays() != null && coupon.getValidDays() > 0
                ? now.plusDays(coupon.getValidDays())
                : (coupon.getEndTime() != null ? coupon.getEndTime() : now.plusDays(DEFAULT_VALID_DAYS));

        UserCouponEntity entity = new UserCouponEntity();
        entity.setUserId(userId);
        entity.setCouponId(couponId);
        entity.setStatus(STATUS_UNUSED);
        entity.setReceivedAt(now);
        entity.setExpireAt(expireAt);
        userCouponMapper.insert(entity);

        log.info("用户 {} 领取优惠券 {}（{}）", userId, couponId, coupon.getName());
        return toUserCouponResponse(entity, coupon, null);
    }

    @Override
    public List<UserCouponResponse> myCoupons(String userId, String status, Long orderAmount) {
        List<UserCouponEntity> mine = userCouponMapper.selectList(
                new LambdaQueryWrapper<UserCouponEntity>()
                        .eq(UserCouponEntity::getUserId, userId)
                        .eq(status != null && !status.isBlank(), UserCouponEntity::getStatus, status)
                        .orderByAsc(UserCouponEntity::getStatus)
                        .orderByDesc(UserCouponEntity::getId));
        if (mine.isEmpty()) {
            return List.of();
        }

        Map<Long, CouponEntity> templates = couponMapper.selectByIds(
                        mine.stream().map(UserCouponEntity::getCouponId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(CouponEntity::getId, Function.identity()));

        return mine.stream()
                .map(entity -> toUserCouponResponse(entity, templates.get(entity.getCouponId()), orderAmount))
                .toList();
    }

    @Override
    @Transactional
    public long redeem(String userId, Long userCouponId, String orderNo, long orderAmount) {
        UserCouponEntity entity = userCouponId == null ? null : userCouponMapper.selectById(userCouponId);
        // 不区分「不存在」与「不属于你」：区分开来等于告诉调用方哪些 id 有效
        if (entity == null || !entity.getUserId().equals(userId)) {
            throw new IllegalArgumentException("优惠券不存在");
        }

        CouponEntity coupon = couponMapper.selectById(entity.getCouponId());
        if (coupon == null) {
            throw new IllegalStateException("优惠券已下架");
        }

        // 门槛在核销前校验一次，给出能指导下一步的提示（还差多少）
        long threshold = coupon.getThreshold() == null ? 0L : coupon.getThreshold();
        if (orderAmount < threshold) {
            throw new IllegalStateException("订单金额未达到使用门槛，还差 " + (threshold - orderAmount) + " 分");
        }

        // 扣减由条件更新裁决：未使用 + 未过期。并发下只有一次能成功 ——
        // 折扣只能减一次钱，这条约束必须落在 SQL 上
        if (userCouponMapper.redeem(userCouponId, userId, orderNo) == 0) {
            throw new IllegalStateException("优惠券不可用（已使用或已过期）");
        }

        long deduct = computeDeduction(coupon, orderAmount);
        log.info("优惠券已核销 userId={} userCouponId={} orderNo={} 抵扣={}分",
                userId, userCouponId, orderNo, deduct);
        return deduct;
    }

    @Override
    @Transactional
    public void unredeem(String userId, Long userCouponId) {
        if (userCouponId == null) {
            return;
        }
        // 只退「已使用」的。条件里带上原状态，避免把一张本来就没用过的券改成未使用 ——
        // 那会让同一张券可以被核销两次
        int updated = userCouponMapper.update(null, new LambdaUpdateWrapper<UserCouponEntity>()
                .eq(UserCouponEntity::getId, userCouponId)
                .eq(UserCouponEntity::getUserId, userId)
                .eq(UserCouponEntity::getStatus, STATUS_USED)
                .set(UserCouponEntity::getStatus, STATUS_UNUSED)
                .set(UserCouponEntity::getOrderNo, null)
                .set(UserCouponEntity::getUsedAt, null));
        if (updated > 0) {
            log.warn("[Coupon] 下单失败，已退还优惠券 userCouponId={} userId={}", userCouponId, userId);
        }
    }

    @Override
    @Transactional
    public int expireOutdated() {
        return userCouponMapper.update(null, new LambdaUpdateWrapper<UserCouponEntity>()
                .eq(UserCouponEntity::getStatus, STATUS_UNUSED)
                .lt(UserCouponEntity::getExpireAt, Times.now())
                .set(UserCouponEntity::getStatus, STATUS_EXPIRED));
    }

    /**
     * 算抵扣金额（分）。
     * <p>
     * 满减券：抵扣不超过订单金额本身 —— 否则「满 10 减 100」会算出负数。
     * 折扣券：先算折扣再向下取整，**宁可少减一分也不能多减**。
     */
    private long computeDeduction(CouponEntity coupon, long orderAmount) {
        if (TYPE_DISCOUNT.equals(coupon.getType())) {
            BigDecimal rate = coupon.getDiscount() == null ? BigDecimal.ONE : coupon.getDiscount();
            long payable = BigDecimal.valueOf(orderAmount)
                    .multiply(rate)
                    .setScale(0, RoundingMode.DOWN)
                    .longValue();
            return Math.max(0L, orderAmount - payable);
        }
        if (TYPE_FIXED.equals(coupon.getType())) {
            long amount = coupon.getAmount() == null ? 0L : coupon.getAmount();
            return Math.min(amount, orderAmount);
        }
        // 未知类型一律抵扣 0：宁可少减，不能因为数据脏而多减钱
        log.warn("[Coupon] 未知的券类型 {}，按 0 抵扣处理 couponId={}", coupon.getType(), coupon.getId());
        return 0L;
    }

    private CouponResponse toCouponResponse(CouponEntity coupon, boolean received) {
        int total = coupon.getTotalCount() == null ? 0 : coupon.getTotalCount();
        int got = coupon.getReceivedCount() == null ? 0 : coupon.getReceivedCount();
        return CouponResponse.builder()
                .id(coupon.getId())
                .name(coupon.getName())
                .type(coupon.getType())
                .ruleText(ruleText(coupon))
                .amount(coupon.getAmount())
                .discount(coupon.getDiscount())
                .threshold(coupon.getThreshold())
                .scopeType(coupon.getScopeType())
                .totalCount(total)
                .receivedCount(got)
                .remainingCount(Math.max(0, total - got))
                .validFrom(coupon.getStartTime())
                .validTo(coupon.getEndTime())
                .received(received)
                .build();
    }

    private UserCouponResponse toUserCouponResponse(UserCouponEntity entity, CouponEntity coupon, Long orderAmount) {
        String usableReason = null;
        Boolean usable = null;
        if (orderAmount != null) {
            usable = true;
            if (!STATUS_UNUSED.equals(entity.getStatus())) {
                usable = false;
                usableReason = STATUS_USED.equals(entity.getStatus()) ? "已使用" : "已过期";
            } else if (entity.getExpireAt() != null && entity.getExpireAt().isBefore(Times.now())) {
                usable = false;
                usableReason = "已过期";
            } else if (coupon != null) {
                long threshold = coupon.getThreshold() == null ? 0L : coupon.getThreshold();
                if (orderAmount < threshold) {
                    usable = false;
                    usableReason = "差 " + (threshold - orderAmount) + " 分可用";
                }
            }
        }

        return UserCouponResponse.builder()
                .id(entity.getId())
                .couponId(entity.getCouponId())
                .name(coupon == null ? "优惠券已下架" : coupon.getName())
                .type(coupon == null ? null : coupon.getType())
                .ruleText(coupon == null ? null : ruleText(coupon))
                .amount(coupon == null ? null : coupon.getAmount())
                .threshold(coupon == null ? null : coupon.getThreshold())
                .status(entity.getStatus())
                .statusText(statusText(entity.getStatus()))
                .orderNo(entity.getOrderNo())
                .receivedAt(entity.getReceivedAt())
                .usedAt(entity.getUsedAt())
                .expireAt(entity.getExpireAt())
                .usable(usable)
                .unusableReason(usableReason)
                .build();
    }

    /** 把券翻译成一句人话。服务端拼一次，免得前端各写一套 */
    private String ruleText(CouponEntity coupon) {
        long threshold = coupon.getThreshold() == null ? 0L : coupon.getThreshold();
        String prefix = threshold <= 0 ? "无门槛" : "满 " + yuan(threshold) + " 元";
        if (TYPE_DISCOUNT.equals(coupon.getType())) {
            BigDecimal rate = coupon.getDiscount() == null ? BigDecimal.ONE : coupon.getDiscount();
            // 0.85 → 8.5 折
            BigDecimal zhe = rate.multiply(BigDecimal.TEN).setScale(1, RoundingMode.HALF_UP);
            return prefix + "享 " + zhe.stripTrailingZeros().toPlainString() + " 折";
        }
        long amount = coupon.getAmount() == null ? 0L : coupon.getAmount();
        return prefix + "减 " + yuan(amount) + " 元";
    }

    private String yuan(long cents) {
        return BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString();
    }

    private String statusText(String status) {
        return switch (status) {
            case STATUS_UNUSED -> "未使用";
            case STATUS_USED -> "已使用";
            case STATUS_EXPIRED -> "已过期";
            default -> status;
        };
    }
}
