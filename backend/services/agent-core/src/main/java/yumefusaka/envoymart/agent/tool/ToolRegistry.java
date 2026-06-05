package yumefusaka.envoymart.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心 —— 以名称索引管理所有可用工具。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.getDefinition().getName(), tool);
    }

    public void registerAll(List<Tool> toolList) {
        toolList.forEach(this::register);
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> listDefinitions() {
        return tools.values().stream()
                .map(Tool::getDefinition)
                .toList();
    }

    public ToolResult execute(ToolCall call) {
        return get(call.getToolName())
                .map(tool -> tool.execute(call))
                .orElseGet(() -> ToolResult.builder()
                        .success(false)
                        .errorMessage("Tool not found: " + call.getToolName())
                        .build());
    }
}
