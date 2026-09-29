package yumefusaka.envoymart.orderservice.model;

import lombok.Data;

/** 支付服务返回的退款结果（Feign 契约，字段名须与 payment-service 的 RefundResponse 一致） */
@Data
public class RefundSnapshot {

    private Long id;
    private String refundNo;
    private Long orderId;
    /** SUCCESS / PENDING / FAILED */
    private String status;
    private Long amount;
}
