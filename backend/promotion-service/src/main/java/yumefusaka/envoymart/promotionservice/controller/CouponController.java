package yumefusaka.envoymart.promotionservice.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.promotionservice.model.CouponResponse;
import yumefusaka.envoymart.promotionservice.model.UserCouponResponse;
import yumefusaka.envoymart.promotionservice.service.CouponService;

import java.util.List;

/**
 * 优惠券。
 * <p>
 * 领券中心对所有登录用户开放；核销是服务间动作，网关对 {@code /coupons/internal/} 一律 404 ——
 * 让用户能自己核销等于让他自己改优惠金额。
 */
@RestController
@RequestMapping("/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping("/available")
    public Result<List<CouponResponse>> available(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId) {
        return Result.success(couponService.availableCoupons(userId));
    }

    @PostMapping("/{id}/receive")
    public Result<UserCouponResponse> receive(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        return Result.success(couponService.receive(userId, id));
    }

    /**
     * 我的券。
     *
     * @param orderAmount 结算页传入订单金额，用于算出每张券「现在能不能用、差多少」
     */
    @GetMapping("/mine")
    public Result<List<UserCouponResponse>> mine(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "orderAmount", required = false) Long orderAmount) {
        return Result.success(couponService.myCoupons(userId, status, orderAmount));
    }

    /**
     * 核销。**服务间动作**，由订单服务在下单时调用。
     * <p>
     * 放在 {@code /internal/} 下而不是 {@code /{id}/...} 下：后者会被
     * {@code /{id}/receive} 那一族的模式抢走匹配，这是本项目已经踩过两次的坑
     * （见购物车与支付的注释）。
     */
    @PostMapping("/internal/redeem")
    public Result<Long> redeem(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestParam("userCouponId") Long userCouponId,
            @RequestParam("orderNo") String orderNo,
            @RequestParam("orderAmount") long orderAmount) {
        return Result.success(couponService.redeem(userId, userCouponId, orderNo, orderAmount));
    }

    /**
     * 退还优惠券 —— 下单中途失败时由订单服务调用。
     * <p>
     * 订单没建成，券不该被吃掉。
     */
    @PostMapping("/internal/unredeem")
    public Result<Void> unredeem(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestParam("userCouponId") Long userCouponId) {
        couponService.unredeem(userId, userCouponId);
        return Result.success(null);
    }
}
