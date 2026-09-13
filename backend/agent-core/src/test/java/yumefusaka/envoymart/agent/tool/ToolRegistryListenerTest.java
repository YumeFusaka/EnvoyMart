package yumefusaka.envoymart.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具调用的观测覆盖 —— 回归防线。
 * <p>
 * 埋点曾经写在 `ToolRegistryToolCallback` 里，而**执行图的计划节点根本不经过那个类**：
 * 三条来路（计划节点 / ReAct 循环 / MCP）里最主要的一条一次都没被统计到。
 * 指标看着有值，实际漏了大头——**埋点位置错了，比没有埋点更有害**，因为它让人以为在看全貌。
 * <p>
 * 现在观测点挂在 {@link ToolRegistry#execute}，三条来路共用。
 */
class ToolRegistryListenerTest {

    private record Recorded(String tool, ToolCallListener.Outcome outcome) {
    }

    private final List<Recorded> recorded = new ArrayList<>();

    private final ToolCallListener listener = (tool, outcome, latencyMs) ->
            recorded.add(new Recorded(tool, outcome));

    private Tool tool(String name, boolean requiresConfirmation) {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder()
                        .name(name).description(name).parameters(Map.of())
                        .requiresConfirmation(requiresConfirmation)
                        .build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.builder().success(true).output("ok").build();
            }
        };
    }

    private ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry(listener);
        registry.register(tool("order_query", false));
        registry.register(tool("order_cancel", true));
        return registry;
    }

    @Test
    void 成功调用被记录为success() {
        registry().execute(new ToolCall("1", "order_query", Map.of()));

        assertThat(recorded).containsExactly(new Recorded("order_query", ToolCallListener.Outcome.SUCCESS));
    }

    @Test
    void 执行失败被记录为error() {
        ToolRegistry registry = registry();
        ToolResult result = registry.execute(new ToolCall("1", "no_such_tool", Map.of()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(recorded).containsExactly(new Recorded("no_such_tool", ToolCallListener.Outcome.ERROR));
    }

    @Test
    void 高危未确认被记录为error而非静默跳过() {
        registry().execute(new ToolCall("1", "order_cancel", Map.of(), false));

        assertThat(recorded)
                .as("被第二道防线拒绝执行也是一次结果，不能不留痕")
                .containsExactly(new Recorded("order_cancel", ToolCallListener.Outcome.ERROR));
    }

    @Test
    void 护栏拦截单独记录为blocked() {
        ToolRegistry registry = registry();
        registry.recordBlocked("order_query");

        assertThat(recorded)
                .as("被拦下的调用没进 execute，但消费了预算，必须能单独看出来")
                .containsExactly(new Recorded("order_query", ToolCallListener.Outcome.BLOCKED));
    }

    @Test
    void 未接入监听器时不报错() {
        ToolRegistry bare = new ToolRegistry();
        bare.register(tool("order_query", false));

        assertThat(bare.execute(new ToolCall("1", "order_query", Map.of())).isSuccess()).isTrue();
        bare.recordBlocked("order_query");
    }
}
