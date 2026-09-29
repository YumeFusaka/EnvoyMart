package yumefusaka.envoymart.paymentservice.service;

import yumefusaka.envoymart.paymentservice.model.CreatePaymentRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentCallbackRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentResponse;

public interface PaymentService {

    PaymentResponse createPayment(String userId, CreatePaymentRequest request);

    PaymentResponse processCallback(PaymentCallbackRequest request);

    PaymentResponse getPayment(String userId, Long orderId);

    /**
     * 记录一条<b>验签失败</b>的回调。
     * <p>
     * 单独开一个入口，是因为验签失败时流程会在校验处中断、根本走不到
     * {@link #processCallback} —— 而那恰恰是最需要留痕的一类请求：
     * 伪造回调的尝试是有价值的排查线索，只记录成功的那些等于把线索丢掉。
     */
    void recordRejectedCallback(PaymentCallbackRequest request, String signature);
}
