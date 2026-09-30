package yumefusaka.envoymart.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 优惠券核销请求。
 * <p>
 * <b>由 order-service 发出，promotion-service 消费。</b>
 * <p>
 * 传的是订单商品明细而不是一个总额：限类目/限商品的券要按「范围内商品的小计」
 * 判门槛、算折扣 —— 只给一个总额，券服务无从知道哪些商品在范围内，
 * 要么放弃校验（券的范围形同虚设），要么按全额算（一元的类目商品凑上九十九元的别的商品，
 * 也能用满 100 减 20 的类目券）。真实电商的优惠计算就是按行来的。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedeemRequest {

    @NotNull(message = "userCouponId 不能为空")
    private Long userCouponId;

    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    @NotEmpty(message = "订单商品明细不能为空")
    @Valid
    private List<RedeemItem> items;
}
