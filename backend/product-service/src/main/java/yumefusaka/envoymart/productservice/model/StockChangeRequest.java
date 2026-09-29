package yumefusaka.envoymart.productservice.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 库存变动请求（扣减 / 回补共用）。
 * <p>
 * 维度是 <b>SKU</b> 而不是商品：价格与库存都挂在 SKU 上，
 * 用商品维度扣减会出现「扣了 90 粒装，180 粒装的库存也少了」。
 * <p>
 * {@code bizType} / {@code bizId} 用于给流水留痕——回补失败、对账、"这批库存是被谁扣的"
 * 都靠它。可以省略，但省略掉的流水只有数量、没有来源，对账时等于没用。
 */
@Data
public class StockChangeRequest {

    @NotNull(message = "skuId 不能为空")
    private Long skuId;

    @NotNull(message = "quantity 不能为空")
    @Min(value = 1, message = "quantity 至少为 1")
    private Integer quantity;

    /** ORDER / AFTER_SALE / MANUAL */
    private String bizType;

    /** 订单号或售后单号 */
    private String bizId;

    private String remark;
}
