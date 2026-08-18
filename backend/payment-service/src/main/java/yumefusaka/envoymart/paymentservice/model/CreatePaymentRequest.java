package yumefusaka.envoymart.paymentservice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreatePaymentRequest {
    @NotNull
    private Long orderId;
    @NotBlank
    private String orderNo;
    @NotBlank
    private String userId;
    @NotNull
    private BigDecimal amount;
}
