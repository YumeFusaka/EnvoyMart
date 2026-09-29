package yumefusaka.envoymart.paymentservice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RefundRequest {

    @NotNull(message = "orderId 不能为空")
    private Long orderId;

    /** 由售后退款时关联售后单；为空表示订单取消等主动退款 */
    private Long afterSaleId;

    /** 退款金额（分）。不传表示全额退（扣除已退部分） */
    private Long amount;

    @NotBlank(message = "退款原因不能为空")
    @Size(max = 255, message = "退款原因最长 255 位")
    private String reason;
}
