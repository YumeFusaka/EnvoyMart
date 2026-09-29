package yumefusaka.envoymart.productservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品详情：SPU 主体 + 规格组 + SKU 列表 + 参数。
 * <p>
 * 无参构造与全参构造是给 {@code BeanUtils.copyProperties} 用的：
 * 读缓存后要用实时库存复制一份再返回，不能就地改缓存里的那个实例。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDetail {

    private Long id;
    private String spuCode;
    private String name;
    private String subtitle;
    private Long categoryId;
    private String categoryName;
    private Long brandId;
    private String brandName;
    private String mainImage;
    private List<String> images;
    private String detailHtml;
    private Integer status;
    private Integer sales;
    private BigDecimal ratingAvg;
    private Integer reviewCount;

    /** 规格组（颜色 / 容量）：前端据此渲染选择器 */
    private List<SpecGroup> specs;
    /** 全部在售 SKU：前端用 specValueIds 把「选中的规格组合」映射到具体 SKU */
    private List<SkuView> skus;
    /** 参数区（与规格不同，它只描述「是什么」，不影响买哪一个） */
    private List<AttributeView> attributes;
}
