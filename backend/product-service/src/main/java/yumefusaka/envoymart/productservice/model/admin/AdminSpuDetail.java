package yumefusaka.envoymart.productservice.model.admin;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.contract.AttributeView;
import yumefusaka.envoymart.contract.SpecGroup;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端的商品详情 —— 也是编辑表单的回显数据。
 * <p>
 * 与公开的 {@code ProductDetail} 分开的<b>关键差别是 SKU 的可见性</b>：
 * 公开详情只返回启用中的 SKU（停用规格组合不该出现在买家面前），
 * 而管理端必须看到全部——否则停用之后就没有任何入口能把它改回来。
 * 两个用途对同一份数据的要求正好相反，用一个类型兼两边只能靠一个布尔参数去分叉，
 * 那种分叉在调用点看不出来（多传一个 true 而已），迟早有人传错。
 */
@Data
@Builder
public class AdminSpuDetail {

    private Long id;
    private String spuCode;
    private String name;
    private String subtitle;
    private Long categoryId;
    private Long brandId;
    private String mainImage;
    private List<String> images;
    private String detailHtml;
    private List<String> tags;
    /** 0 下架 / 1 上架 */
    private Integer status;
    private Integer sales;
    private BigDecimal ratingAvg;
    private Integer reviewCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<SpecGroup> specs;
    private List<AdminSku> skus;
    private List<AttributeView> attributes;

    /** SKU 的编辑态：比公开的 SKU 多了启停状态与规格名映射 */
    @Data
    @Builder
    public static class AdminSku {
        private Long id;
        private String skuCode;
        private Long price;
        private Long originalPrice;
        private Integer stock;
        private String image;
        /** 1 启用 / 0 停用 */
        private Integer status;
        /**
         * 规格名 → 规格值名，表单据此回显每个 SKU 的规格组合。
         * <p>
         * 不给「规格值 id 列表」：表单提交时用的就是名字，回显用同一套表达，
         * 前端不必在两套标识之间来回翻译。
         */
        private Map<String, String> specValues;
    }
}
