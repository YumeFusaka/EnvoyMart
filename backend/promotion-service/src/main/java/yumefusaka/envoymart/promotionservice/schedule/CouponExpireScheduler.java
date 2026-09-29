package yumefusaka.envoymart.promotionservice.schedule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.promotionservice.service.CouponService;

/**
 * 优惠券过期任务。
 * <p>
 * 没有它的话，过期券会一直以「未使用」的身份留在用户的券包里 ——
 * 用户点进去才发现用不了，而这本来是可以提前告诉他的。
 * <p>
 * 每小时跑一次就够：券的过期精度不需要到分钟，而每小时的 UPDATE 成本可以忽略。
 */
@Slf4j
@Component
public class CouponExpireScheduler {

    private final CouponService couponService;

    public CouponExpireScheduler(CouponService couponService) {
        this.couponService = couponService;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void expireOutdated() {
        try {
            int expired = couponService.expireOutdated();
            if (expired > 0) {
                log.info("[Coupon] 已过期 {} 张优惠券", expired);
            }
        } catch (Exception e) {
            // 任务抛出去只会让调度线程记一笔，下一趟照跑；显式接住是为了让
            // 「过期一直失败」在日志里看得见
            log.error("[Coupon] 优惠券过期任务执行失败", e);
        }
    }
}
