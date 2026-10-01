package yumefusaka.envoymart.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 券可用性预览请求。
 * <p>
 * <b>由 order-service 发出，promotion-service 消费。</b>
 * <p>
 * 与 {@link RedeemRequest} 的差别只有一处：没有订单号 —— 预览时订单还不存在。
 * 不是「复用 RedeemRequest 然后忽略 orderNo」：那样 orderNo 上的 {@code @NotBlank}
 * 会变成一条永远为假的要求，下一个人读到它只能靠猜。
 * <p>
 * items 的构造方与核销侧是同一段代码（order-service 从购物车 + SKU 快照组装），
 * 这是「预览说能用、提交却被拒」不再复发的根据：两边吃的是同一份输入。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponPreviewRequest {

    @NotEmpty(message = "订单商品明细不能为空")
    @Valid
    private List<RedeemItem> items;
}
