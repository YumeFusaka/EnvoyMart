package yumefusaka.envoymart.promotionservice.service;

import yumefusaka.envoymart.contract.CouponPreview;
import yumefusaka.envoymart.contract.RedeemItem;
import yumefusaka.envoymart.contract.RedeemRequest;
import yumefusaka.envoymart.promotionservice.model.CouponResponse;
import yumefusaka.envoymart.promotionservice.model.UserCouponResponse;

import java.util.List;

public interface CouponService {

    /** 领券中心：当前可领的券，带「是否已领过」标记 */
    List<CouponResponse> availableCoupons(String userId);

    /** 领取一张券。每人每张限领一次；已领完或未开始都会如实拒绝 */
    UserCouponResponse receive(String userId, Long couponId);

    /**
     * 我的券（券包视图）。
     * <p>
     * 不算「这张券在某个订单上能不能用」—— 那件事需要订单行明细，
     * 见 {@link #preview(String, List)}。
     *
     * @param status UNUSED / USED / EXPIRED；为空则全部
     */
    List<UserCouponResponse> myCoupons(String userId, String status);

    /**
     * 结算页预览：这批商品下，我的每张未使用券能不能用、能抵多少。
     * <p>
     * 与 {@link #redeem(String, RedeemRequest)} <b>吃同一份 items、走同一段判定</b>：
     * 不可用时的理由就是核销会抛的那句话。此前预览只拿到一个订单总额，
     * 于是限类目的券可以「预览说可用、提交被拒」——两边输入不同，结论必然可能分叉。
     *
     * @param items 订单行明细，由 order-service 从购物车与 SKU 快照组装（与结算同一段代码）
     */
    List<CouponPreview> preview(String userId, List<RedeemItem> items);

    /**
     * 核销一张券并返回<b>抵扣金额（分）</b>。
     * <p>
     * 请求带订单行明细：限类目/限商品的券按「范围内商品小计」判门槛、算折扣
     * （见 {@link RedeemRequest}）—— 只传总额的话，券的范围形同虚设。
     * <p>
     * 扣减由 SQL 的条件更新裁决（未使用 + 未过期），并发下只有一次能成功 ——
     * 折扣只能减一次钱。
     *
     * @throws IllegalStateException 券不可用（已用 / 已过期 / 不满足门槛 / 不适用所选商品）
     */
    long redeem(String userId, RedeemRequest request);

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
