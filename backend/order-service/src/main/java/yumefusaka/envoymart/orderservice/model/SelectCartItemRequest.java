package yumefusaka.envoymart.orderservice.model;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SelectCartItemRequest {

    @NotNull(message = "selected 不能为空")
    private Boolean selected;
}
