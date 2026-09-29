package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 商品列表项（SPU 粒度）。
 * <p>
 * <b>由 product-service 发出，AI 服务（商品工具）消费。</b>
 * <p>
 * 价格是<b>区间</b>而不是单值：一个 SPU 下有多个 SKU，各卖各的价。
 * 只回一个价格就得在服务端随便挑一个，那是在替用户做决定。
 * <p>
 * 金额一律是<b>分</b>。没有 {@code price} 这种「元」字段——曾经有过，
 * 结果消费方把它当元用、又因为类型对不上拿到 null，输出成「商品名 (null 元)」
 * 还被模型当成事实说给用户。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
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
     * <p>
     * 契约模块的唯一样例外：它是<b>纯函数式的字段解析</b>，不引入状态也不引入依赖，
     * 比让每个消费方各写一份更安全。
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
