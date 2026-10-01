package yumefusaka.envoymart.contract;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    /**
     * 本行商品所属类目及其全部祖先，由近及远（自身在最前）。
     * <p>
     * <b>传路径而不是一个类目 id</b>：类目是棵树，运营配「营养保健」级目的券时，
     * 预期是「这个类目及其下所有商品」。只传叶子类目的话，券服务要么等值匹配
     * （配在一级类目上的券谁也用不了），要么得回查商品服务（核销在订单事务里，
     * 不该为一次类目换算多挂一个下游依赖）。祖先链是下单那一刻快照里的既有信息，
     * 随行带来即可 —— 判定退化成一次集合求交。
     * <p>
     * 商品没有类目时为空集合，限类目的券不会把它算进范围。
     */
    @NotEmpty(message = "商品类目路径不能为空")
    private List<Long> categoryPath;

    /** 行小计（分），已乘过数量。不含运费 */
    @NotNull(message = "行小计不能为空")
    @Min(value = 0, message = "行小计不能为负")
    private Long subtotal;
}
