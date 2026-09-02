package yumefusaka.envoymart.productservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
