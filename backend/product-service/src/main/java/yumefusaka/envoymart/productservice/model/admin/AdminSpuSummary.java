package yumefusaka.envoymart.productservice.model.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端商品列表项。
 * <p>
 * 与公开的 {@code ProductSummary} 不是同一个类型，因为两者要的东西不一样：
 * 公开列表要「展示给买家看的卖点」，管理列表要「运营判断用的状态」——
 * 编码、上下架状态、最后修改时间、SKU 数量，这些在买家那边一个都不该出现。
 * 用一个类型兼两边，就得给公开接口加上它不需要的字段，且没有一处能让人看出它们是两个用途。
 */
@Data
@Builder
public class AdminSpuSummary {

    private Long id;
    private String spuCode;
    private String name;
    private String subtitle;
    private Long categoryId;
    private String categoryName;
    private Long brandId;
    private String brandName;
    private String mainImage;
    /** 全部 SKU 中的最低 / 最高售价，单位「分」。没有任何 SKU 时为 null */
    private Long minPrice;
    private Long maxPrice;
    /** SKU 数量（含停用）。0 说明这个商品还不能卖 */
    private Integer skuCount;
    private Integer totalStock;
    private Integer sales;
    /** 0 下架 / 1 上架 */
    private Integer status;
    private LocalDateTime updatedAt;
}
