package yumefusaka.envoymart.orderservice.model;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.contract.CouponPreview;

import java.util.List;

/**
 * 结算页试算结果。
 * <p>
 * 每个金额都由服务端算，前端只负责显示：结算页自己拿券面文案反推折扣
 * （曾经用正则从「满 100 减 10 元」里解出数字）时，「9 折」这类券的取整方向
 * 一旦与核销侧不同，结算页与订单详情就会差一分钱，而两边看上去都对。
 */
@Data
@Builder
public class OrderPreviewResponse {

    /** 商品小计（分）= 各行单价 × 数量之和，不含运费与优惠 */
    private Long totalAmount;
    private Long freightAmount;
    /** 所选券的抵扣（分）。没选券、或所选券在这批商品上不可用时为 0 */
    private Long discountAmount;
    /** 应付 = 商品小计 + 运费 - 抵扣 */
    private Long payAmount;

    /** 参与结算的商品行数 */
    private Integer itemCount;

    /**
     * 其中已失效（下架 / 不存在）的行数。
     * <p>
     * <b>这类行不计金额，也不会进券的作用域</b>，而提交时会被同一条件整单挡下。
     * 不带这个数的话，页面上是一份「看起来能提交」的账，点下去才知道不行 ——
     * 与券预览要修的正是同一类毛病。
     */
    private Integer unavailableCount;

    /** 我的每张未使用券在这批商品上的结论（能不能用、能抵多少、不能用是为什么） */
    private List<CouponPreview> coupons;
}
