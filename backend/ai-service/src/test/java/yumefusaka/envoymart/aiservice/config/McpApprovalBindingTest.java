package yumefusaka.envoymart.aiservice.config;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.tool.PendingAction;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** MCP 审批载荷必须绑定工具名、完整业务参数和 operationId。 */
class McpApprovalBindingTest {

    private static final PendingAction APPROVED = PendingAction.of(
            "cart_update", Map.of("cartItemId", 7, "quantity", 2), "run-1:step-0");

    @Test
    void 完全匹配的确认动作才允许兑换() {
        assertThat(McpServerConfig.matchesApprovedAction("cart_update",
                Map.of("cartItemId", 7L, "quantity", 2), true, APPROVED)).isTrue();
    }

    @Test
    void 参数篡改或缺少确认都拒绝() {
        assertThat(McpServerConfig.matchesApprovedAction("cart_update",
                Map.of("cartItemId", 8, "quantity", 2), true, APPROVED)).isFalse();
        assertThat(McpServerConfig.matchesApprovedAction("cart_update",
                Map.of("cartItemId", 7, "quantity", 2), false, APPROVED)).isFalse();
        assertThat(McpServerConfig.matchesApprovedAction("cart_update",
                Map.of("cartItemId", 7, "quantity", 2), true,
                PendingAction.of("cart_update", Map.of("cartItemId", 7, "quantity", 2), null))).isFalse();
    }

    @Test
    void 结算令牌的内部requestId由服务端补回且不要求MCP调用方暴露() {
        PendingAction approved = PendingAction.of("cart_checkout",
                Map.of("receiverName", "Alice", "requestId", "signed-id"), "run-2:step-0");
        assertThat(McpServerConfig.matchesApprovedAction("cart_checkout",
                Map.of("receiverName", "Alice"), true, approved)).isTrue();
    }
}
