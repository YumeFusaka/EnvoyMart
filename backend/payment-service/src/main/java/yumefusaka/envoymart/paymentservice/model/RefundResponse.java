package yumefusaka.envoymart.paymentservice.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 退款单。金额单位「分」 */
@Data
@Builder
public class RefundResponse {

    private Long id;
    private String refundNo;
    private Long orderId;
    private Long afterSaleId;
    private Long amount;
    private String status;
    private String reason;
    private String channelRefundNo;
    private LocalDateTime createdAt;
    private LocalDateTime refundedAt;
}
