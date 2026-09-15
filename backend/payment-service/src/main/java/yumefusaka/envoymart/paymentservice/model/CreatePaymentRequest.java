package yumefusaka.envoymart.paymentservice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建支付单的请求。
 * <p>
 * <b>刻意没有 userId</b>：归属以网关注入的身份为准，请求体是调用方可改的。
 * 曾经保留过这个字段并标了 {@code @NotBlank}——取值早已改成从请求头拿，
 * 但校验注解没跟着删，导致不带 userId 的请求被 {@code @Valid} 直接拒掉，
 * 接口实际处于不可用状态。**字段的校验语义要和取值来源一起改，只改一处就是坏的。**
 */
@Data
public class CreatePaymentRequest {
    @NotNull
    private Long orderId;
    @NotBlank
    private String orderNo;
    @NotNull
    private BigDecimal amount;
}
