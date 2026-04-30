package yumefusaka.envoymart.product.model.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ProductResponse {

    private Long id;
    private String name;
    private String subtitle;
    private String category;
    private String brand;
    private BigDecimal price;
    private Integer stock;
    private Integer monthlySales;
    private String image;
    private String salesCopy;
    private String description;
    private List<String> tags;
}
