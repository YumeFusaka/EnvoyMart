package yumefusaka.envoymart.orderservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 购物车条目。金额单位「分」，与商品服务一致 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItemResponse {

    private Long id;
    private Long spuId;
    private Long skuId;
    private String name;
    /** 形如 "规格:400IU×90粒;包装:瓶装" */
    private String specText;
    private String image;
    private Long price;
    private Integer quantity;
    /** SKU 的当前库存，用于在购物车里就提示「库存不足」而不是等到下单才失败 */
    private Integer stock;
    private Long subtotal;
    private Boolean selected;
    /**
     * 是否仍在售且有货。
     * <p>
     * 失效条目**留在购物车里并标记出来**，而不是直接删掉：用户会想知道
     * 「我加的那个东西去哪了」。直接消失的条目看起来像系统出了问题。
     */
    private Boolean available;
}
