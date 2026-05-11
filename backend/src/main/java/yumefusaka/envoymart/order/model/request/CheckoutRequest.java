package yumefusaka.envoymart.order.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CheckoutRequest {

    @NotBlank(message = "收货人不能为空")
    private String recipientName;

    @NotBlank(message = "联系电话不能为空")
    private String recipientPhone;

    @NotBlank(message = "收货地址不能为空")
    private String address;
}
