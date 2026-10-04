package yumefusaka.envoymart.aiservice.model;

import lombok.Data;

/**
 * 购物车条目 —— 加购与查购物车的返回。
 * <p>
 * 字段刻意精简到「模型转述给用户时会用到的那些」：名称、规格、单价、数量、小计、是否可用。
 * 购物车实体上还有一堆归属校验与审计字段，那些对用户没有意义，让模型看见只会挤占上下文。
 */
@Data
public class AgentCartItem {

    private Long id;
    private Long spuId;
    private Long skuId;
    private String name;
    private String specText;
    /** 单价，单位分 */
    private Long price;
    private Integer quantity;
    /** 小计，单位分 */
    private Long subtotal;
    /** 该规格是否仍可购买。下架或售罄时为 false —— 模型据此提示用户，而不是照样催他下单 */
    private Boolean available;
}
