package yumefusaka.envoymart.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高危工具的确认门禁：没有用户确认时，模型无法自主执行。
 */
class ToolRegistryApprovalTest {

    private final AtomicInteger executions = new AtomicInteger();

    private ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder()
                        .name("order_cancel")
                        .description("取消订单")
                        .requiresConfirmation(true)
                        .parameters(Map.of())
                        .build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                executions.incrementAndGet();
                return ToolResult.builder().success(true).output("已取消").build();
            }
        });
        return registry;
    }

    @Test
    void 未确认时高危工具不执行() {
        ToolResult result = registry().execute(new ToolCall("1", "order_cancel", Map.of()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isPendingApproval()).isTrue();
        assertThat(executions.get()).isZero();
    }

    @Test
    void 确认后高危工具正常执行() {
        ToolResult result = registry().execute(new ToolCall("1", "order_cancel", Map.of(), true));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("已取消");
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void 能列出需要确认的工具() {
        assertThat(registry().confirmationRequiredTools()).containsExactly("order_cancel");
    }
}
