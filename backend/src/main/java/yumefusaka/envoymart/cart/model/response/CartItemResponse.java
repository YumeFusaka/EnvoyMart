package yumefusaka.envoymart.cart.model.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
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
