package yumefusaka.envoymart.paymentservice.model;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建支付单的请求。
 * <p>
 * <b>只有 orderId</b>。金额、订单号、归属一概不在这里传——它们都由 order-service 裁决：
 * <ul>
 *   <li>{@code userId} 取自网关注入的身份，请求体是调用方可改的；</li>
 *   <li>{@code amount} / {@code orderNo} 曾经在这里并直接落库，等于让调用方决定"这单多少钱"，
 *       实测能把 198 元的订单建成 0.01 元的支付单。</li>
 * </ul>
 * 这里不留"仅供参考"的字段：一个传了却不生效的字段，下一个人会以为它有用。
 */
@Data
public class CreatePaymentRequest {
    @NotNull
    private Long orderId;
}
