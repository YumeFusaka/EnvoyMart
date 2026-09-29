package yumefusaka.envoymart.paymentservice.service;

import yumefusaka.envoymart.paymentservice.model.RefundRequest;

import java.util.List;
import yumefusaka.envoymart.paymentservice.model.RefundResponse;

public interface RefundService {

    /** 用户发起的退款（售后场景）。会校验支付单归属 */
    RefundResponse refund(String userId, RefundRequest request);

    /**
     * 订单相关操作触发的退款（如已支付订单被关闭）。
     * <p>
     * 没有调用方身份：它表达的是「这笔订单的钱要还回去」，不是「某个用户在操作」。
     * 因此**不对外暴露成 HTTP 接口**，只供服务间调用。
     */
    RefundResponse refundForOrder(Long orderId, Long afterSaleId, Long amount, String reason);

    List<RefundResponse> listByOrder(String userId, Long orderId);
}
