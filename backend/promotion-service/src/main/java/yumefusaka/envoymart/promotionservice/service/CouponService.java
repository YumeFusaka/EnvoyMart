package yumefusaka.envoymart.promotionservice.service;

import yumefusaka.envoymart.promotionservice.model.CouponResponse;
import yumefusaka.envoymart.promotionservice.model.UserCouponResponse;

import java.util.List;

public interface CouponService {

    /** 领券中心：当前可领的券，带「是否已领过」标记 */
    List<CouponResponse> availableCoupons(String userId);

    /** 领取一张券。每人每张限领一次；已领完或未开始都会如实拒绝 */
    UserCouponResponse receive(String userId, Long couponId);

    /**
     * 我的券。
     *
     * @param status UNUSED / USED / EXPIRED；为空则全部
     * @param orderAmount 结算页传入订单金额，用于算出每张券「现在能不能用」；
     *                    为空则不计算可用性
     */
    List<UserCouponResponse> myCoupons(String userId, String status, Long orderAmount);

    /**
     * 核销一张券并返回<b>抵扣金额（分）</b>。
     * <p>
     * 扣减由 SQL 的条件更新裁决（未使用 + 未过期），并发下只有一次能成功 ——
     * 折扣只能减一次钱。
     *
     * @throws IllegalStateException 券不可用（已用 / 已过期 / 不满足门槛）
     */
    long redeem(String userId, Long userCouponId, String orderNo, long orderAmount);

    /**
     * 退还一张已核销但未实际使用的券。
     * <p>
     * 用于「券已核销、订单却没建成」的情形：库存不足、建单失败都会走到这里。
     * 不带这个补偿的话，一次失败的下单就会把用户的券白白吃掉。
     */
    void unredeem(String userId, Long userCouponId);

    /**
     * 把过期的券置为 EXPIRED。
     * <p>
     * 没有它的话，过期券会一直以 UNUSED 的身份留在「我的券」里，
     * 用户点进去才发现用不了。
     *
     * @return 本次过期的数量
     */
    int expireOutdated();
}
