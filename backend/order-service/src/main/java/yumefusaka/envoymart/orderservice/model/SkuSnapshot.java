package yumefusaka.envoymart.orderservice.model;

import lombok.Data;

/**
 * 商品服务返回的 SKU 快照（Feign 契约）。
 * <p>
 * 字段名必须与 product-service 的 {@code SkuSnapshot} 完全一致 ——
 * 两边是各自独立的类，靠 JSON 字段名对齐，改名不会有任何编译期提示。
 */
@Data
public class SkuSnapshot {

    private Long id;
    private Long spuId;
    private String spuName;
    private String specText;
    private String image;
    /** 单位「分」 */
    private Long price;
    private Integer stock;
    /** 1 在售 / 0 下架 */
    private Integer status;
}
