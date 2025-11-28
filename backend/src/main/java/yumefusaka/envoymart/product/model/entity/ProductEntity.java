package yumefusaka.envoymart.product.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product")
public class ProductEntity {

    @TableId
    private Long id;
    private String name;
    private String subtitle;
    private String category;
    private String brand;
    private String tags;
    private BigDecimal price;
    private Integer stock;
    private Integer monthlySales;
    private String image;
    private String salesCopy;
    private String semanticKeywords;
    private String description;
    private LocalDateTime createdAt;
}
