package yumefusaka.envoymart.promotionservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.contract.CouponPreview;
import yumefusaka.envoymart.contract.CouponPreviewRequest;
import yumefusaka.envoymart.contract.RedeemRequest;
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
     * 我的券（券包）。
     * <p>
     * 这里<b>不算「在某个订单上能不能用」</b>：那件事需要订单行明细，
     * 由结算页走 order-service 的 {@code POST /orders/preview} 触发
     * （见 {@link #preview}）。曾经这个接口收一个 orderAmount 就地算，
     * 但它只有总额、没有商品明细，限类目券的结论与核销必然分叉。
     */
    @GetMapping("/mine")
    public Result<List<UserCouponResponse>> mine(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestParam(value = "status", required = false) String status) {
        return Result.success(couponService.myCoupons(userId, status));
    }

    /**
     * 结算页预览。**服务间动作**，由订单服务在用户进结算页与切换券时调用。
     * <p>
     * 传的是订单行明细而不是订单金额，判定与核销共用同一段代码
     * （见 {@link CouponService#preview(String, List)}）—— 用户看到的
     * 「为什么不能用」与提交被拒时的那句话逐字一致。
     */
    @PostMapping("/internal/preview")
    public Result<List<CouponPreview>> preview(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @Valid @RequestBody CouponPreviewRequest request) {
        return Result.success(couponService.preview(userId, request.getItems()));
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
            @Valid @RequestBody RedeemRequest request) {
        return Result.success(couponService.redeem(userId, request));
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
