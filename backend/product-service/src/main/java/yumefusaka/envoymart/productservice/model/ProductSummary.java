package yumefusaka.envoymart.productservice.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 列表项（SPU 粒度）。
 * <p>
 * 价格是**区间**而不是单值：一个 SPU 下有多个 SKU，各卖各的价。
 * 只回一个价格就得在服务端随便挑一个，那是在替用户做决定。
 */
@Data
@Builder
public class ProductSummary {

    private Long id;
    private String name;
    private String subtitle;
    private Long categoryId;
    private String categoryName;
    private Long brandId;
    private String brandName;
    private String mainImage;
    /** 单位「分」。前端展示时除以 100 */
    private Long minPrice;
    private Long maxPrice;
    private Integer sales;
    private BigDecimal ratingAvg;
    private Integer reviewCount;
    /** 该 SPU 下所有 SKU 的库存合计 */
    private Integer totalStock;
    private List<String> tags;

    /**
     * 库里存的是逗号分隔字符串，对外统一是数组。
     * <p>
     * 转换收在这里而不是各写一份：详情接口解析了它、搜索接口曾经漏掉，于是同一个商品
     * 在 {@code /products/{id}} 有标签、在 {@code /products/search} 返回 null ——
     * 两处各写一遍同样的解析，漏掉一处不会有任何报错，只有对着接口比才发现。
     */
    public static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }
}
