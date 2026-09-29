package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 退款单。金额单位「分」。
 * <p>
 * <b>由 payment-service 发出，order-service 消费。</b>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundResponse {

    private Long id;
    private String refundNo;
    private Long orderId;
    private Long afterSaleId;
    private Long amount;

    /** SUCCESS / PENDING / FAILED */
    private String status;

    private String reason;
    private String channelRefundNo;
    private LocalDateTime createdAt;
    private LocalDateTime refundedAt;
}
