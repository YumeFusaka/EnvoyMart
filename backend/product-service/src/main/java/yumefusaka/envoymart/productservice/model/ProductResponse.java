package yumefusaka.envoymart.productservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Arrays;
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

    /**
     * 实体与 ES 索引里的 tags 都是逗号分隔的字符串，对外接口统一是数组。
     * <p>
     * 转换收在这里而不是各写一份：详情接口解析了它，搜索接口曾经漏掉，于是同一个商品
     * 在 {@code /products/{id}} 有标签、在 {@code /products/search} 返回 null——
     * 两处各写一遍同样的解析，漏掉一处不会有任何报错，只有对着接口比才发现。
     */
    public static List<String> splitTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }
}
