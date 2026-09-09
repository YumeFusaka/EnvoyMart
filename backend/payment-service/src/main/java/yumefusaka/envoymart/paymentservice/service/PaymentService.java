package yumefusaka.envoymart.paymentservice.service;

import yumefusaka.envoymart.paymentservice.model.CreatePaymentRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentCallbackRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentResponse;

public interface PaymentService {

    PaymentResponse createPayment(String userId, CreatePaymentRequest request);

    PaymentResponse processCallback(PaymentCallbackRequest request);

    PaymentResponse getPayment(String userId, Long orderId);
}
