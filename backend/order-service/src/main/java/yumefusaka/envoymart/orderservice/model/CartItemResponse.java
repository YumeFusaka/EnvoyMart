package yumefusaka.envoymart.orderservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItemResponse {

    private Long id;
    private Long productId;
    private String name;
    private String image;
    private BigDecimal price;
    private Integer quantity;
    private Integer stock;
    private BigDecimal subtotal;
}
