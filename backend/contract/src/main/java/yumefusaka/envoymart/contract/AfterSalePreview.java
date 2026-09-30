package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 售后资格的预览结果。
 * <p>
 * 让用户在填表之前就知道「能不能退、最多退多少」，而不是提交完才被拒 ——
 * 后者会让人觉得是在碰运气。
 * <p>
 * <b>放在 contract 而不是 order-service 的内部模型里</b>：这份判定的消费方不止前端。
 * Agent 的售后流程要让用户拿到<b>和页面一致</b>的说法，就得读同一个结论；
 * 若它在自己的模块里再定一份同名字段，两处会各自漂移 ——
 * 而「同一个问题两个答案」正是这套判定最不该出的错（判定规则本身已经收在
 * {@code AfterSalePolicyEngine} 一处，调用侧不该再裂开）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfterSalePreview {

    private Long orderItemId;
    private String type;
    /** 政策判定能不能受理 */
    private boolean eligible;
    /** 不能受理时的原因，直接展示给用户 */
    private String reason;
    /** 最多可退金额（分） */
    private Long maxRefundAmount;
    /** 该订单行的实付小计（分），供对比 */
    private Long itemSubtotal;
    /** 依据的政策文档编号，前端可据此展示「查看政策原文」 */
    private String docRef;
    /** 附加条件说明 */
    private String requirements;
}
