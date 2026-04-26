package yumefusaka.envoymart.paymentservice.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class PaymentResponse {
    private Long id;
    private Long orderId;
    private String orderNo;
    private BigDecimal amount;
    private String status;
    private String transactionNo;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
}
