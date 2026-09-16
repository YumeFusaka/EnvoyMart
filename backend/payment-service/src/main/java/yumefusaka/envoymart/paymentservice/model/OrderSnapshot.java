package yumefusaka.envoymart.paymentservice.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单服务返回的订单摘要 —— 只取支付单需要的字段。
 * <p>
 * order-service 返回的订单对象字段更多（收件人、地址、明细行），这里不逐个复刻：
 * 未声明的字段会在反序列化时被忽略，而每多声明一个字段，就多一处"下游改了名、
 * 这里悄悄变成 null"的可能。支付只关心「哪张单、多少钱、能不能付」。
 */
@Data
public class OrderSnapshot {
    private Long id;
    private String orderNo;
    private BigDecimal totalAmount;
    private String status;
}
