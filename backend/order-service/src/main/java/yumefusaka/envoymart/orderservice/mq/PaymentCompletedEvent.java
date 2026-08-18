package yumefusaka.envoymart.orderservice.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付完成事件（当支付成功时发布）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCompletedEvent {
    private Long orderId;
    private String orderNo;
    private String transactionNo;
    private BigDecimal amount;
    private LocalDateTime paidAt;
}
