package yumefusaka.envoymart.paymentservice.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.paymentservice.model.PaymentCallbackRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 支付回调验签。
 * <p>
 * 回调端点必须匿名可达（支付渠道不会带用户 JWT），所以它不能靠平台鉴权保护——
 * 只能靠<b>调用方无法伪造的签名</b>。没有这一层时，任何人都能 POST 一条
 * {@code {"orderId":1,"status":"SUCCESS"}} 把订单标成已支付。
 * <p>
 * 未配置密钥时<b>拒绝一切回调</b>（fail-closed）。回调是资金相关的入口，
 * "配置缺失就放行"在这里的代价是真金白银，宁可拒绝也不能放宽。
 */
@Slf4j
@Component
public class PaymentCallbackVerifier {

    /** 渠道在回调请求头里携带的签名：HMAC-SHA256(secret, orderId|transactionNo|status) 的十六进制 */
    public static final String SIGNATURE_HEADER = "X-Pay-Signature";

    private final byte[] secret;

    public PaymentCallbackVerifier(@Value("${envoymart.payment.callback-secret:}") String secret) {
        this.secret = (secret == null || secret.isBlank()) ? null : secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret == null) {
            log.warn("[Payment] 未配置 envoymart.payment.callback-secret，支付回调将全部被拒绝");
        }
    }

    public void verify(PaymentCallbackRequest request, String providedSignature) {
        if (secret == null) {
            throw new IllegalStateException("未配置支付回调密钥，拒绝处理回调");
        }
        String expected = sign(request);
        // 常量时间比较：普通 equals 会在首个不同字符处提前返回，泄漏签名前缀
        if (providedSignature == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSignature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            log.warn("[Payment] 回调签名校验失败 orderId={}", request.getOrderId());
            throw new IllegalArgumentException("回调签名校验失败");
        }
    }

    private String sign(PaymentCallbackRequest request) {
        String payload = request.getOrderId() + "|" + request.getTransactionNo() + "|" + request.getStatus();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算回调签名失败", e);
        }
    }
}
