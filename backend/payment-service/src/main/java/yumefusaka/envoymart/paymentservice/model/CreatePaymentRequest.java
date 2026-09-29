package yumefusaka.envoymart.paymentservice.model;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreatePaymentRequest {

    @NotNull(message = "orderId 不能为空")
    private Long orderId;

    /** ALIPAY / WECHAT / MOCK。不传按 MOCK 处理 */
    private String channel;

    /** APP / WEB / QR */
    private String payType;
}
