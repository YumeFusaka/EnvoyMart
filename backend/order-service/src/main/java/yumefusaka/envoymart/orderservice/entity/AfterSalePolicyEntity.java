package yumefusaka.envoymart.orderservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 售后政策。
 * <p>
 * 这条是「规则引擎」与「知识库」的交汇点：
 * <ul>
 *   <li>规则引擎读这张表得出「能不能退」的<b>确定性结论</b> ——
 *       涉及金额与时间窗，模型算错就是资损；</li>
 *   <li>{@code docRef} 指向知识库文档编号，「为什么」由检索给出并附带原文引用。</li>
 * </ul>
 * 两条腿各司其职：<b>规则给结论，检索给依据</b>。
 */
@Data
@TableName("after_sale_policy")
public class AfterSalePolicyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 为 NULL 表示全类目默认政策；否则只覆盖该类目 */
    private Long categoryId;
    /** REFUND_ONLY / RETURN_REFUND / EXCHANGE */
    private String type;
    private Integer returnable;
    /** 无理由退货天数 */
    private Integer returnDays;
    /** 质量问题可退天数 */
    private Integer qualityDays;
    /** 最高可退比例（如拆封的食品只能退部分） */
    private java.math.BigDecimal maxRefundRatio;
    private String requirements;
    /** 对应知识库文档编号，用于溯源 */
    private String docRef;
}
