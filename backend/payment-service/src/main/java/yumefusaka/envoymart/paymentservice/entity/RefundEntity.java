package yumefusaka.envoymart.paymentservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 退款单。
 * <p>
 * 与支付单分离，因为一次支付可以有多次部分退款 —— 把退款做成支付单上的一个状态，
 * 就只能表达「全退了」和「没退」两种情况。
 */
@Data
@TableName("refund")
public class RefundEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String refundNo;
    private Long paymentId;
    private Long orderId;
    /** 由售后退款时关联售后单；已支付订单被取消等场景为空 */
    private Long afterSaleId;
    /** 主动退款的幂等键（售后单之外的第二种退款来源），如 "CANCEL:{orderNo}"。为空表示走 afterSaleId 那一套 */
    private String bizNo;
    private String userId;
    /** 单位「分」，不得超过对应支付单的剩余可退金额 */
    private Long amount;
    /** PENDING / SUCCESS / FAILED */
    private String status;
    private String reason;
    private String channelRefundNo;
    private LocalDateTime createdAt;
    private LocalDateTime refundedAt;
}
