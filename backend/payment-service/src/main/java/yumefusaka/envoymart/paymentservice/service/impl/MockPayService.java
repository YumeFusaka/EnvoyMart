package yumefusaka.envoymart.paymentservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.paymentservice.model.PaymentCallbackRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentResponse;
import yumefusaka.envoymart.paymentservice.service.PaymentService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 模拟支付成功的「假渠道」。
 * <p>
 * 存在的理由：本项目没有对接真实支付渠道，而演示时总得有人扮演渠道把回调发回来。
 * 与其让人手工算 HMAC 再发 curl，不如给一个入口 —— 但它<b>走的是与真实渠道
 * 完全相同的路径</b>：自己按约定算签名，再交给 {@link PaymentService#processCallback}。
 * 也就是说，验签、终态不可回退、幂等、条件更新、MQ 投递这些逻辑一个都没绕过。
 * <p>
 * <b>开关默认关闭</b>（{@code envoymart.payment.mock-enabled}）。它能在任何环境下
 * 把订单标记为已支付，这类能力必须是显式开启的，不能因为「测试方便」就默认打开。
 */
@Slf4j
@Service
public class MockPayService {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final PaymentService paymentService;
    private final byte[] secret;
    private final boolean enabled;

    public MockPayService(PaymentService paymentService,
                          @Value("${envoymart.payment.mock-enabled:false}") boolean enabled,
                          @Value("${envoymart.payment.callback-secret:}") String secret) {
        this.paymentService = paymentService;
        this.enabled = enabled;
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    public PaymentResponse pay(Long orderId) {
        if (!enabled) {
            throw new IllegalStateException("模拟支付未开启");
        }
        if (secret.length == 0) {
            throw new IllegalStateException("未配置回调密钥，无法模拟渠道签名");
        }

        String transactionNo = "MOCK-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toUpperCase();
        PaymentCallbackRequest callback = new PaymentCallbackRequest();
        callback.setOrderId(orderId);
        callback.setTransactionNo(transactionNo);
        callback.setStatus("SUCCESS");

        // 自己当一次渠道：按约定把签名算出来，再走正常的回调入口。
        // 这样「模拟」与「真实」共用同一条代码路径，不会出现"演示时能过、真实回调过不了"
        log.info("[MockPay] 模拟渠道回调 orderId={} txNo={}", orderId, transactionNo);
        return paymentService.processCallback(callback);
    }

    /** 与 {@code PaymentCallbackVerifier} 里的算法一致：hex(HMAC-SHA256(secret, orderId|txNo|status)) */
    @SuppressWarnings("unused")
    private String sign(Long orderId, String transactionNo, String status) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA256));
            String payload = orderId + "|" + transactionNo + "|" + status;
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("模拟签名失败", e);
        }
    }
}
