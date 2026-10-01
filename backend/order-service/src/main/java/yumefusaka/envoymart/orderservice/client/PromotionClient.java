package yumefusaka.envoymart.orderservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.contract.CouponPreview;
import yumefusaka.envoymart.contract.CouponPreviewRequest;
import yumefusaka.envoymart.contract.RedeemRequest;

import java.util.List;

/**
 * 营销服务客户端 —— 下单时核销优惠券。
 * <p>
 * 核销放在营销服务而不是订单侧自己改状态：那里有行锁语义的条件更新、
 * 有门槛校验，绕过它自己写库等于把那些保护全部丢掉。
 */
@FeignClient(name = "promotion-service", url = "${services.promotion-service-url:http://127.0.0.1:9007}")
public interface PromotionClient {

    /**
     * 核销优惠券，返回抵扣金额（分）。
     * <p>
     * 带用户身份：券是私有数据，营销服务要据此校验归属。
     * <p>
     * 传订单商品明细而不是总额：限类目/限商品的券要按「范围内商品小计」判门槛、算折扣
     * （见 {@link RedeemRequest}）。
     */
    @PostMapping("/coupons/internal/redeem")
    Result<Long> redeem(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                        @RequestBody RedeemRequest request);

    /**
     * 结算页试算：这批商品下我的每张券能不能用、能抵多少。
     * <p>
     * 与 {@link #redeem} 传同一份 items、在营销服务里走同一段判定 ——
     * 用户看到的「为什么不能用」与提交被拒时的那句话逐字一致。
     */
    @PostMapping("/coupons/internal/preview")
    Result<List<CouponPreview>> preview(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                        @RequestBody CouponPreviewRequest request);

    /**
     * 退还优惠券（把已核销的改回未使用）。
     * <p>
     * 下单中途失败时用：订单没建成，券不该被吃掉。
     */
    @PostMapping("/coupons/internal/unredeem")
    Result<Void> unredeem(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                          @RequestParam("userCouponId") Long userCouponId);
}
