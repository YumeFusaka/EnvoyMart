package yumefusaka.envoymart.contract;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 库存变动请求（扣减 / 回补共用）。
 * <p>
 * 维度是 <b>SKU</b> 而不是商品：价格与库存都挂在 SKU 上，
 * 用商品维度扣减会出现「扣了 90 粒装，180 粒装的库存也少了」。
 * <p>
 * {@code bizType} / {@code bizId} 用于给流水留痕——回补失败、对账、"这批库存是被谁扣的"
 * 都靠它。可以省略，但省略掉的流水只有数量、没有来源，对账时等于没用。
 * <p>
 * <b>由 order-service 发出，product-service 消费。</b>
 * 校验注解跟着契约走：约束是接口语义的一部分，两边不该各写一套。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockChangeRequest {

    @NotNull(message = "skuId 不能为空")
    private Long skuId;

    @NotNull(message = "quantity 不能为空")
    @Min(value = 1, message = "quantity 至少为 1")
    private Integer quantity;

    /** ORDER_DEDUCT / ORDER_CANCEL / AFTER_SALE_RETURN … 落进库存流水的来源分类 */
    private String bizType;

    /** 订单号或售后单号 */
    private String bizId;

    /** 落进库存流水的备注。对账时「这条扣减是什么时候因为什么发生的」只能靠它 */
    private String remark;
}
