package yumefusaka.envoymart.aiservice.service;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** T1 写操作的确定性副作用契约，不依赖下游服务。 */
class AgentSideEffectVerificationTest {

    @Test
    void 同一operationId重复确认只执行一次真实副作用() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("write", calls, false));

        ToolCall first = new ToolCall("1", "write", Map.of("skuId", 101), true, "u1", "run-1:step-0");
        ToolCall retry = new ToolCall("2", "write", Map.of("skuId", 101), true, "u1", "run-1:step-0");

        assertThat(registry.execute(first).isSuccess()).isTrue();
        assertThat(registry.execute(retry).isSuccess()).isTrue();
        assertThat(calls).hasValue(1);
    }

    @Test
    void 瞬时故障不缓存恢复后同operationId可以重试() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("remote", calls, true));

        ToolCall call = new ToolCall("1", "remote", Map.of(), true, "u1", "run-2:step-0");
        assertThat(registry.execute(call).isTransientFailure()).isTrue();
        assertThat(registry.execute(call).isSuccess()).isTrue();
        assertThat(calls).hasValue(2);
    }

    private static Tool tool(String name, AtomicInteger calls, boolean firstTransient) {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name(name).description(name).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                int count = calls.incrementAndGet();
                if (firstTransient && count == 1) {
                    return ToolResult.builder().success(false).transientFailure(true)
                            .errorMessage("下游暂时不可用").build();
                }
                return ToolResult.builder().success(true).output("ok").build();
            }
        };
    }
}
