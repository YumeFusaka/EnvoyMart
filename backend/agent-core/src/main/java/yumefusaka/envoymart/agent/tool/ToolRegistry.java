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
                .map(tool -> {
                    // 高危操作的第二道防线：即便模型自主决定调用，没有用户确认也拒绝执行
                    if (tool.getDefinition().isRequiresConfirmation() && !call.isConfirmed()) {
                        return ToolResult.builder()
                                .success(false)
                                .pendingApproval(true)
                                .errorMessage("该操作需要用户确认后才能执行：" + call.getToolName())
                                .build();
                    }
                    return tool.execute(call);
                })
                .orElseGet(() -> ToolResult.builder()
                        .success(false)
                        .errorMessage("Tool not found: " + call.getToolName())
                        .build());
    }

    /** 列出所有需要用户确认的高危工具名。 */
    public List<String> confirmationRequiredTools() {
        return tools.values().stream()
                .filter(tool -> tool.getDefinition().isRequiresConfirmation())
                .map(tool -> tool.getDefinition().getName())
                .toList();
    }
}
