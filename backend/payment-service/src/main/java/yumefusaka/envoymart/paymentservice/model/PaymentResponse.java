package yumefusaka.envoymart.paymentservice.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 支付单。金额单位「分」 */
@Data
@Builder
public class PaymentResponse {

    private Long id;
    private String paymentNo;
    private Long orderId;
    private String orderNo;
    private Long amount;
    private String channel;
    private String payType;
    private String status;
    private String transactionNo;
    private LocalDateTime paidAt;
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;
}
