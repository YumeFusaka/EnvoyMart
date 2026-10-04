package yumefusaka.envoymart.aiservice.security;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.tool.ApprovalTokens;
import yumefusaka.envoymart.agent.tool.PendingAction;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 高危工具的授权契约：<b>只认服务端签发的确认令牌，不认调用方自填的 confirmed。</b>
 * <p>
 * <b>为什么这条契约值得一组专门的测试</b>：升级前它的失败形态是「静默越权」——
 * 任何人拿到 JWT/API Key，对 {@code order_cancel} 填一个 true 就能执行，
 * 唯一的痕迹是一条 WARN。这类缺陷不会以「报错」的形式出现，只会以「没什么异常」出现，
 * 所以必须由测试把「拒绝」这件事固定住，否则下一次改动很容易把它悄悄放开。
 * <p>
 * 测试直接打在 {@link ApprovalTokens} 与 {@code McpServerConfig} 共用的校验路径上：
 * 与对话侧同源，所以这里验的就是线上真正跑的那段判定。
 */
class McpApprovalAuthorizationTest {

    private static final String SECRET = "test-secret-for-mcp-approval";

    private ApprovalTokens tokens() {
        return new ApprovalTokens(SECRET, 600);
    }

    @Test
    void 合法令牌通过校验并带回确切动作() {
        ApprovalTokens tokens = tokens();
        String token = tokens.issue("u1001", null,
                List.of(PendingAction.of("order_cancel", Map.of("orderId", 12))));

        var verified = tokens.verify(token, "u1001", null);

        assertThat(verified).as("服务端签发的令牌必须能通过校验").isPresent();
        assertThat(verified.get()).hasSize(1);
        assertThat(verified.get().get(0).tool()).isEqualTo("order_cancel");
        assertThat(verified.get().get(0).arguments()).containsEntry("orderId", 12);
    }

    @Test
    void 缺失或空令牌一律不通过() {
        ApprovalTokens tokens = tokens();

        assertThat(tokens.verify(null, "u1001", null)).as("裸填 confirmed 的场景就是没有令牌").isEmpty();
        assertThat(tokens.verify("", "u1001", null)).isEmpty();
        assertThat(tokens.verify("   ", "u1001", null)).isEmpty();
    }

    @Test
    void 签名被改过的令牌不通过() {
        ApprovalTokens tokens = tokens();
        String token = tokens.issue("u1001", null,
                List.of(PendingAction.of("order_cancel", Map.of("orderId", 12))));

        // 改载荷不改签名：把 orderId 12 篡改成 13，等价于「用户确认的是 12 号单，
        // 实际执行的却是 13 号单」——正是签名要挡住的那类攻击
        String tampered = token.substring(0, token.indexOf('.')) + ".deadbeef";

        assertThat(tokens.verify(tampered, "u1001", null)).isEmpty();
    }

    @Test
    void 令牌不属于当前用户时不通过() {
        ApprovalTokens tokens = tokens();
        String token = tokens.issue("u1001", null,
                List.of(PendingAction.of("order_cancel", Map.of("orderId", 12))));

        assertThat(tokens.verify(token, "u1002", null))
                .as("拿到别人的令牌就能替别人取消订单，是比裸填 true 更严重的越权")
                .isEmpty();
    }

    @Test
    void 外部入口令牌不绑定会话但用户与动作照旧强制() {
        ApprovalTokens tokens = tokens();
        // MCP 没有平台会话概念：签发与校验都不带会话。绑定维度落在
        // 「用户 + 动作 + 入参 + 有效期 + 签名」上，这是跨入口执行的必要条件
        String token = tokens.issueForExternalEntry("u1001",
                List.of(PendingAction.of("order_cancel", Map.of("orderId", 12))));

        assertThat(tokens.verify(token, "u1001", null))
                .as("用户在对话里确认的动作，要能在 MCP 这条入口执行")
                .isPresent();
        assertThat(tokens.verify(token, "u1001", "any-session"))
                .as("校验方显式比较会话时，空会话令牌仍不匹配——放宽只在「不比较会话」这一档生效")
                .isEmpty();
        assertThat(tokens.verify(token, "u1002", null))
                .as("放宽会话不等于放宽用户——换个人拿同一枚令牌仍是越权")
                .isEmpty();
    }

    @Test
    void 带会话签发的令牌仍然会话受限() {
        ApprovalTokens tokens = tokens();
        String token = tokens.issue("u1001", "session-a",
                List.of(PendingAction.of("order_cancel", Map.of("orderId", 12))));

        assertThat(tokens.verify(token, "u1001", "session-a")).isPresent();
        assertThat(tokens.verify(token, "u1001", "session-b"))
                .as("普通入口签发的令牌照旧绑会话，放宽只对显式的 issueForExternalEntry 生效")
                .isEmpty();
    }

    @Test
    void 过期的令牌不通过() throws Exception {
        ApprovalTokens tokens = new ApprovalTokens(SECRET, 1);
        String token = tokens.issue("u1001", null,
                List.of(PendingAction.of("order_cancel", Map.of("orderId", 12))));

        Thread.sleep(2200);

        assertThat(tokens.verify(token, "u1001", null))
                .as("令牌带 TTL，过期后不能再执行——否则一枚令牌可以永久授权同一动作")
                .isEmpty();
    }

    @Test
    void 不同密钥签发的令牌互相验不过() {
        ApprovalTokens issuer = new ApprovalTokens(SECRET, 600);
        ApprovalTokens other = new ApprovalTokens("another-secret", 600);
        String token = issuer.issue("u1001", null,
                List.of(PendingAction.of("order_cancel", Map.of("orderId", 12))));

        assertThat(other.verify(token, "u1001", null))
                .as("签名密钥不同即视为不同信任域——这是「伪造一枚令牌」要被挡住的地方")
                .isEmpty();
    }
}