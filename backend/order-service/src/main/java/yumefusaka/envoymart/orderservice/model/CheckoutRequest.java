package yumefusaka.envoymart.orderservice.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CheckoutRequest {

    @NotBlank
    private String recipientName;
    @NotBlank
    private String recipientPhone;
    @NotBlank
    private String address;
}
