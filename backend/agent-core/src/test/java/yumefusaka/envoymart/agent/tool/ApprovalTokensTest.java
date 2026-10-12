package yumefusaka.envoymart.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审批令牌的签发与验签。
 * <p>
 * 这批用例把「拒绝」当成主路径来写：令牌这东西的成败不在「有效的能不能过」，
 * 而在「无效的一条都不能过」——签错的、改过的、过期的、不是发给你的，
 * 漏掉任何一类，确认卡片对用户就只是一次转发。
 */
class ApprovalTokensTest {

    private static String signPayload(Map<String, Object> payload) throws Exception {
        String body = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                new tools.jackson.databind.ObjectMapper().writeValueAsBytes(payload));
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return body + "." + java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void 老签名缺少恢复时长不自动升级且新时长必须是签入的有效整数() throws Exception {
        var clock = java.time.Clock.fixed(java.time.Instant.ofEpochSecond(1791687600), java.time.ZoneOffset.UTC);
        var codec = new ApprovalTokens(SECRET, 600, clock);
        Map<String, Object> payload = new java.util.LinkedHashMap<>(Map.of("u", "u1", "s", "s1", "e", 1791688200L,
                "a", List.of(Map.of("t", "order_cancel", "p", Map.of("orderId", 12), "o", "original-op"))));
        String legacy = signPayload(payload);
        assertThat(codec.verifyGrantForRecovery(legacy, "u1", "s1").orElseThrow().recoverySeconds()).isZero();
        assertThat(codec.verify(legacy, "u1", "s1")).isPresent();
        var expired = new ApprovalTokens(SECRET, 600, java.time.Clock.offset(clock, java.time.Duration.ofSeconds(601)));
        assertThat(expired.verify(legacy, "u1", "s1")).isEmpty();
        assertThat(expired.verifyGrantForRecovery(legacy, "u1", "s1").orElseThrow().recoverySeconds()).isZero();
        for (Object invalid : List.of(-1, 86401, 86400.5, "86400", "invalid")) {
            payload.put("r", invalid);
            assertThat(codec.verifyGrantForRecovery(signPayload(payload), "u1", "s1")).as(String.valueOf(invalid)).isEmpty();
        }
    }

    private static final String SECRET = "unit-test-secret";
    private static final List<PendingAction> CANCEL =
            List.of(PendingAction.of("order_cancel", Map.of("orderId", 12)));

    private static ApprovalTokens tokens() {
        return new ApprovalTokens(SECRET);
    }

    @Test
    void 签发的令牌能验回原样的载荷() {
        String token = tokens().issue("u1", "s1", CANCEL);

        Optional<List<PendingAction>> verified = tokens().verify(token, "u1", "s1");

        assertThat(verified).isPresent();
        assertThat(verified.get()).hasSize(1);
        assertThat(verified.get().get(0).tool()).isEqualTo("order_cancel");
        assertThat(verified.get().get(0).arguments()).containsEntry("orderId", 12);
    }

    @Test
    void 密钥相同的两个实例互相认() {
        String token = new ApprovalTokens(SECRET).issue("u1", "s1", CANCEL);

        assertThat(new ApprovalTokens(SECRET).verify(token, "u1", "s1")).isPresent();
    }

    @Test
    void 密钥不同的实例互不认() {
        String token = tokens().issue("u1", "s1", CANCEL);

        assertThat(new ApprovalTokens("another-secret").verify(token, "u1", "s1"))
                .as("换个密钥就验不过，正是签名要的效果；多实例部署必须显式配置同一个密钥")
                .isEmpty();
    }

    @Test
    void 载荷被改过就验不过() {
        String token = tokens().issue("u1", "s1", CANCEL);
        char flipped = token.charAt(0) == 'A' ? 'B' : 'A';

        assertThat(tokens().verify(flipped + token.substring(1), "u1", "s1"))
                .as("签名的输入变了，签名自然对不上")
                .isEmpty();
    }

    @Test
    void 签名被改过就验不过() {
        String token = tokens().issue("u1", "s1", CANCEL);
        String body = token.substring(0, token.indexOf('.'));
        String signature = token.substring(token.indexOf('.') + 1);
        char flipped = signature.charAt(0) == 'A' ? 'B' : 'A';

        assertThat(tokens().verify(body + "." + flipped + signature.substring(1), "u1", "s1"))
                .as("改签名不能让载荷跟着变——这一支要是过了，等于没有签名")
                .isEmpty();
    }

    @Test
    void 换用户或换会话就验不过() {
        String token = tokens().issue("u1", "s1", CANCEL);

        assertThat(tokens().verify(token, "u2", "s1")).isEmpty();
        assertThat(tokens().verify(token, "u1", "s2")).isEmpty();
    }

    @Test
    void 超长输入直接拒绝() {
        String huge = "a".repeat(9000);

        assertThat(tokens().verify(huge, "u1", "s1"))
                .as("验签前先看长度：签名比对的成本不该由外部输入决定")
                .isEmpty();
    }

    @Test
    void 各种不成形的输入一律拒绝而不是抛异常() {
        ApprovalTokens tokens = tokens();

        assertThat(tokens.verify(null, "u1", "s1")).isEmpty();
        assertThat(tokens.verify("", "u1", "s1")).isEmpty();
        assertThat(tokens.verify("没有点号的字符串", "u1", "s1")).isEmpty();
        assertThat(tokens.verify(".", "u1", "s1")).isEmpty();
        assertThat(tokens.verify("bm90LWpzb24=.deadbeef", "u1", "s1")).isEmpty();
    }

    @Test
    void 空载荷不给签发() {
        assertThatThrownBy(() -> tokens().issue("u1", "s1", List.of()))
                .as("没有待确认动作时不该走到签发的分支——放出去就是一个能「批准」任何事的空令牌")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** TTL 的边界靠真实时间跨过去；这里只留一秒，代价是这一条用例慢两秒 */
    @Test
    void 过期之后验不过() throws InterruptedException {
        String token = new ApprovalTokens(SECRET, 1).issue("u1", "s1", CANCEL);

        assertThat(tokens().verify(token, "u1", "s1")).isPresent();
        Thread.sleep(2200);

        assertThat(tokens().verify(token, "u1", "s1"))
                .as("确认卡片可以在页面上放很久，执行窗口不能跟着一起放")
                .isEmpty();
        assertThat(tokens().verifyForRecovery(token, "u1", "s1")).isPresent();
        assertThat(tokens().verifyForRecovery(token, "u2", "s1")).isEmpty();
        assertThat(tokens().verifyForRecovery(token, "u1", "s2")).isEmpty();
    }

    @Test
    void 每个动作签入独立操作标识且结算不混入批次请求号() {
        var codec = tokens();
        String token = codec.issue("u1", "s1", List.of(
                PendingAction.of("cart_checkout", Map.of("receiverName", "Alice")),
                PendingAction.of("ticket_create", Map.of("title", "售后咨询"))));
        var actions = codec.verify(token, "u1", "s1").orElseThrow();
        assertThat(actions).extracting(PendingAction::operationId).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(actions.getFirst().arguments()).containsExactlyEntriesOf(Map.of("receiverName", "Alice"));
        assertThat(actions.getFirst().arguments()).doesNotContainKey("requestId");
        assertThat(codec.verify(token, "u1", "s1").orElseThrow()).isEqualTo(actions);
    }

    @Test
    void 同批重复操作标识拒绝签发且恢复不能放过篡改() {
        assertThatThrownBy(() -> tokens().issue("u1", "s1", List.of(
                PendingAction.of("cart_add", Map.of("skuId", 1), "same"),
                PendingAction.of("order_cancel", Map.of("orderId", 2), "same"))))
                .isInstanceOf(IllegalArgumentException.class);
        String token = tokens().issue("u1", "s1", CANCEL);
        assertThat(tokens().verifyForRecovery("A" + token.substring(1), "u1", "s1")).isEmpty();
    }
}
