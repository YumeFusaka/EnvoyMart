package yumefusaka.envoymart.paymentservice.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单服务返回的订单摘要 —— 只取支付单需要的字段。
 * <p>
 * order-service 返回的订单对象字段更多（收件人、地址、明细行），这里不逐个复刻：
 * 未声明的字段会在反序列化时被忽略，而每多声明一个字段，就多一处「下游改了名、
 * 这里悄悄变成 null」的可能。支付只关心「哪张单、该付多少、还能不能付」。
 * <p>
 * 取 {@code payAmount} 而不是 {@code totalAmount}：后者是商品总额，
 * 真实应收的是「总额 + 运费 - 优惠」。用错了会多收运费或少收优惠的差额。
 */
@Data
public class OrderSnapshot {

    private Long id;
    private String orderNo;
    private Long totalAmount;
    /** 实付金额（分）。建支付单用它 */
    private Long payAmount;
    /** CREATED / PAID / ... 见 order-service 的 OrderStatus */
    private String status;
    /** 支付截止时间。前端据此显示倒计时，超时后不应再建支付单 */
    private LocalDateTime expireAt;
}
