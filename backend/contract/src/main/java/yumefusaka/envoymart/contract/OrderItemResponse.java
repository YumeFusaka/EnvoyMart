package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 订单行。存的是下单那一刻的快照，商品之后改价改名都不影响这里 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemResponse {

    private Long id;
    private Long spuId;
    private Long skuId;
    private String spuName;

    /** 形如 "规格:400IU×90粒;包装:瓶装" */
    private String skuSpecText;
    private String skuImage;

    /** 单位「分」 */
    private Long unitPrice;
    private Integer quantity;
    private Long subtotal;
}
