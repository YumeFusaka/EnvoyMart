package yumefusaka.envoymart.paymentservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 支付回调流水。
 * <p>
 * <b>验签失败的记录也要落库</b>：被伪造的回调是有价值的排查线索，
 * 只记录成功日志等于把线索丢掉。
 * <p>
 * {@code payload} 原样保留：对账、纠纷，以及「渠道说回调了但订单没变」这类问题时，
 * 唯一能自证的就是原始报文。
 */
@Data
@TableName("payment_callback_log")
public class PaymentCallbackLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private String transactionNo;
    private String status;
    private String payload;
    private String signature;
    /** 1 验签通过 / 0 未通过 */
    private Integer verified;
    private LocalDateTime createdAt;
}
