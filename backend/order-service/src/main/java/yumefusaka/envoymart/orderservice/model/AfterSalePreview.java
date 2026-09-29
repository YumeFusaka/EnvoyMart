package yumefusaka.envoymart.orderservice.model;

import lombok.Builder;
import lombok.Data;

/**
 * 售后资格的预览结果。
 * <p>
 * 让用户在填表之前就知道「能不能退、最多退多少」，而不是提交完才被拒 ——
 * 后者会让人觉得是在碰运气。
 */
@Data
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
