package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一张券在「这批商品」上的预览结论。
 * <p>
 * <b>由 promotion-service 发出，order-service 消费</b>，最终渲染在结算页上。
 * <p>
 * 关键约束：{@link #usable} 与 {@link #unusableReason} <b>由核销侧的同一段判定产生</b>——
 * 不可用时的理由就是核销会抛的那句话本身，不是另写一套说法。两边各写一套措辞，
 * 迟早会出现「预览说差 20 元、提交说不在适用范围」这种同一次点击里的两种说法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponPreview {

    /** 用户券 id（不是模板 id）：核销时传的就是它 */
    private Long id;

    private String name;

    /** 服务端拼好人话的规则，如「满 100 减 20 元」 */
    private String ruleText;

    /** 能不能用。null 只出现在数据异常兜底，正常判定非 true 即 false */
    private Boolean usable;

    /** 不可用的原因（可用时为 null）。措辞与核销拒绝时逐字一致 */
    private String unusableReason;

    /**
     * 这张券在本单能抵多少（分），可用时才有意义。
     * <p>
     * <b>由服务端算</b>：前端曾经拿 {@code ruleText} 正则解出折扣率自己乘一遍，
     * 于是「9 折」的券在前端按 0.9 反推、在后端按 {@code setScale(DOWN)} 兜底，
     * 差的那一分钱让结算页与订单详情对不上。金额只有一个出处。
     */
    private Long deductAmount;
}
