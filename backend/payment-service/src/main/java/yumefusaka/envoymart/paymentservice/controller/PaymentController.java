package yumefusaka.envoymart.paymentservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.paymentservice.model.CreatePaymentRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentCallbackRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentResponse;
import yumefusaka.envoymart.paymentservice.model.RefundRequest;
import yumefusaka.envoymart.paymentservice.model.RefundResponse;
import yumefusaka.envoymart.paymentservice.security.PaymentCallbackVerifier;
import yumefusaka.envoymart.paymentservice.service.PaymentService;
import yumefusaka.envoymart.paymentservice.service.RefundService;
import yumefusaka.envoymart.paymentservice.service.impl.MockPayService;

import java.util.List;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final RefundService refundService;
    private final PaymentCallbackVerifier callbackVerifier;
    private final MockPayService mockPayService;

    public PaymentController(PaymentService paymentService,
                             RefundService refundService,
                             PaymentCallbackVerifier callbackVerifier,
                             MockPayService mockPayService) {
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.callbackVerifier = callbackVerifier;
        this.mockPayService = mockPayService;
    }

    @PostMapping
    public Result<PaymentResponse> createPayment(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @Valid @RequestBody CreatePaymentRequest request) {
        return Result.success(paymentService.createPayment(userId, request));
    }

    /**
     * 支付渠道回调 —— 匿名可达，因此以签名而非平台鉴权为准。
     * <p>
     * 验签失败时**也要落一条流水**再抛：伪造回调的尝试是最需要留痕的一类请求，
     * 而它在校验处就中断了，走不到正常的处理路径。
     */
    @PostMapping("/callback")
    public Result<PaymentResponse> callback(
            @RequestHeader(value = PaymentCallbackVerifier.SIGNATURE_HEADER, required = false) String signature,
            @Valid @RequestBody PaymentCallbackRequest request) {
        try {
            callbackVerifier.verify(request, signature);
        } catch (RuntimeException e) {
            paymentService.recordRejectedCallback(request, signature);
            throw e;
        }
        return Result.success(paymentService.processCallback(request));
    }

    @GetMapping("/{orderId}")
    public Result<PaymentResponse> getPayment(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("orderId") Long orderId) {
        return Result.success(paymentService.getPayment(userId, orderId));
    }

    /**
     * 模拟支付成功 —— 演示用。
     * <p>
     * 它扮演的是「渠道」：自己按约定算签名，再走与真实回调<b>完全相同</b>的处理路径。
     * 开关默认关闭（{@code envoymart.payment.mock-enabled}），因为这条路径能把订单
     * 标记为已支付，不该在任何环境里默认可用。
     */
    @PostMapping("/{orderId}/mock-pay")
    public Result<PaymentResponse> mockPay(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("orderId") Long orderId) {
        // 先按归属查一次：模拟支付也不该成为「付别人的单」的入口
        paymentService.getPayment(userId, orderId);
        return Result.success(mockPayService.pay(orderId));
    }

    /** 用户发起的退款（售后场景）。归属由支付单决定，不采信请求体 */
    @PostMapping("/refunds")
    public Result<RefundResponse> refund(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @Valid @RequestBody RefundRequest request) {
        return Result.success(refundService.refund(userId, request));
    }

    @GetMapping("/refunds/{orderId}")
    public Result<List<RefundResponse>> listRefunds(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("orderId") Long orderId) {
        return Result.success(refundService.listByOrder(userId, orderId));
    }
}
