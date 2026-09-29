package yumefusaka.envoymart.orderservice.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 订单商品明细事件。粒度是 SKU，与订单行一致 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemEvent {

    private Long skuId;
    private String skuName;
    private Integer quantity;
    /** 单位「分」 */
    private Long price;
}
