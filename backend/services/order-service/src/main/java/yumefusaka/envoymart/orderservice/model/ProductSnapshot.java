package yumefusaka.envoymart.orderservice.model;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductSnapshot {

    private Long id;
    private String name;
    private String image;
    private BigDecimal price;
    private Integer stock;
}
