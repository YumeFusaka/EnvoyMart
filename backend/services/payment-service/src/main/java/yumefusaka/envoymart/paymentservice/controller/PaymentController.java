package yumefusaka.envoymart.paymentservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.paymentservice.model.CreatePaymentRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentCallbackRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentResponse;
import yumefusaka.envoymart.paymentservice.service.PaymentService;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public Result<PaymentResponse> createPayment(@Valid @RequestBody CreatePaymentRequest request) {
        return Result.success(paymentService.createPayment(request));
    }

    @PostMapping("/callback")
    public Result<PaymentResponse> callback(@Valid @RequestBody PaymentCallbackRequest request) {
        return Result.success(paymentService.processCallback(request));
    }

    @GetMapping("/{orderId}")
    public Result<PaymentResponse> getPayment(@PathVariable Long orderId) {
        return Result.success(paymentService.getPayment(orderId));
    }
}
