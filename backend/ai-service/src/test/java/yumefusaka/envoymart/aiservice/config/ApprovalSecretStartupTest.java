package yumefusaka.envoymart.aiservice.config;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.tool.ApprovalTokens;
import yumefusaka.envoymart.agent.tool.PendingAction;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalSecretStartupTest {
    @Test
    void 生产缺少稳定签名配置必须拒绝启动() {
        assertThatThrownBy(() -> AiAgentConfig.requireStableApprovalSecret(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AiAgentConfig.requireStableApprovalSecret(" ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 相同既有配置跨校验实例认可原签名和原操作标识() {
        String secret = "test-stable-approval-secret-32-bytes";
        assertThatCode(() -> AiAgentConfig.requireStableApprovalSecret(secret)).doesNotThrowAnyException();
        String token = new ApprovalTokens(secret).issue("u1", "s1", List.of(PendingAction.of("cart_add", Map.of("skuId", 1), "original-op")));
        assertThat(new ApprovalTokens(secret).verifyForRecovery(token, "u1", "s1").orElseThrow().getFirst().operationId())
                .isEqualTo("original-op");
    }
}
