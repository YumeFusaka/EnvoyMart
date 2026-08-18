package yumefusaka.envoymart.orderservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StockDeductRequest {

    private Long productId;
    private Integer quantity;
}
