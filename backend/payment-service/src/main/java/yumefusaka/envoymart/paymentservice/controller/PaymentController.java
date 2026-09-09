package yumefusaka.envoymart.paymentservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.paymentservice.model.CreatePaymentRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentCallbackRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentResponse;
import yumefusaka.envoymart.paymentservice.security.PaymentCallbackVerifier;
import yumefusaka.envoymart.paymentservice.service.PaymentService;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentCallbackVerifier callbackVerifier;

    public PaymentController(PaymentService paymentService, PaymentCallbackVerifier callbackVerifier) {
        this.paymentService = paymentService;
        this.callbackVerifier = callbackVerifier;
    }

    @PostMapping
    public Result<PaymentResponse> createPayment(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @Valid @RequestBody CreatePaymentRequest request) {
        return Result.success(paymentService.createPayment(userId, request));
    }

    /**
     * 支付渠道回调 —— 匿名可达，因此以签名而非平台鉴权为准。
     */
    @PostMapping("/callback")
    public Result<PaymentResponse> callback(
            @RequestHeader(value = PaymentCallbackVerifier.SIGNATURE_HEADER, required = false) String signature,
            @Valid @RequestBody PaymentCallbackRequest request) {
        callbackVerifier.verify(request, signature);
        return Result.success(paymentService.processCallback(request));
    }

    @GetMapping("/{orderId}")
    public Result<PaymentResponse> getPayment(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable Long orderId) {
        return Result.success(paymentService.getPayment(userId, orderId));
    }
}
