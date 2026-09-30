package yumefusaka.envoymart.contract;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 核销请求里的一行商品（见 {@link RedeemRequest}）。
 * <p>
 * 只需要作用域判定与金额计算要用的三个字段：券服务不该看到订单的全貌 ——
 * 它要知道的是「这行商品在不在我的范围内、值多少钱」，不是收货人是谁、住哪里。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedeemItem {

    @NotNull(message = "spuId 不能为空")
    private Long spuId;

    /** 一级类目。商品没有类目时为空，限类目的券不会把它算进范围 */
    private Long categoryId;

    /** 行小计（分），已乘过数量。不含运费 */
    @NotNull(message = "行小计不能为空")
    @Min(value = 0, message = "行小计不能为负")
    private Long subtotal;
}
