package yumefusaka.envoymart.orderservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 调商品服务变更库存的请求（Feign 契约）。
 * <p>
 * 维度是 <b>SKU</b>：价格与库存都挂在 SKU 上，用商品维度扣减会出现
 * 「扣了 90 粒装，180 粒装的库存也少了」。
 * <p>
 * {@code bizType} / {@code bizId} 会落到商品服务的库存流水里 ——
 * 回补失败、对账、"这批库存是被谁扣的"都靠它。省略掉的流水只有数量、没有来源，
 * 对账时等于没用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockChangeRequest {

    private Long skuId;
    private Integer quantity;
    /** ORDER / AFTER_SALE / MANUAL */
    private String bizType;
    /** 订单号 */
    private String bizId;
    private String remark;
}
