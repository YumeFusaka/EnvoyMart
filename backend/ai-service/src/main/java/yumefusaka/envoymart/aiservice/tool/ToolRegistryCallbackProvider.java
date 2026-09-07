package yumefusaka.envoymart.aiservice.tool;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

/**
 * 把 ToolRegistry 中的全部工具发布给 Spring AI（供 MCP Server 注册）。
 */
public class ToolRegistryCallbackProvider implements ToolCallbackProvider {

    private final ToolRegistry toolRegistry;
    private final MeterRegistry meterRegistry;

    public ToolRegistryCallbackProvider(ToolRegistry toolRegistry, MeterRegistry meterRegistry) {
        this.toolRegistry = toolRegistry;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return toolRegistry.listDefinitions().stream()
                .map(definition -> (ToolCallback) new ToolRegistryToolCallback(
                        toolRegistry, definition, null, meterRegistry))
                .toArray(ToolCallback[]::new);
    }
}
