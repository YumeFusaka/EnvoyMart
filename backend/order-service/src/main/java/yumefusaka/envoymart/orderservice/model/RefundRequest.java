package yumefusaka.envoymart.orderservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 调支付服务退款的请求（Feign 契约，字段名须与 payment-service 的 RefundRequest 一致） */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundRequest {

    private Long orderId;
    private Long afterSaleId;
    /** 单位「分」。为空表示退剩余全部 */
    private Long amount;
    private String reason;
}
