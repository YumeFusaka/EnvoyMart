package yumefusaka.envoymart.paymentservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 支付单。
 * <p>
 * 金额以「分」为单位的整数：资金路径上的浮点误差不只是显示问题 ——
 * 「退款金额不得超过支付金额」这类判断会因为 0.1 + 0.2 != 0.3 而失效。
 */
@Data
@TableName("payment")
public class PaymentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String paymentNo;
    private Long orderId;
    private String orderNo;
    private String userId;
    private Long amount;
    /** ALIPAY / WECHAT / MOCK */
    private String channel;
    /** APP / WEB / QR */
    private String payType;
    /** PENDING / SUCCESS / FAILED / CLOSED */
    private String status;
    /** 渠道流水号。与支付单号分开：前者由渠道生成，对账时以它为准 */
    private String transactionNo;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
