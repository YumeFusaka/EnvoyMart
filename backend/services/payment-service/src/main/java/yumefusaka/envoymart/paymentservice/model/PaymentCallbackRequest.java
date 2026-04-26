package yumefusaka.envoymart.paymentservice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PaymentCallbackRequest {
    @NotNull
    private Long orderId;
    @NotBlank
    private String transactionNo;
    @NotBlank
    private String status;
}
