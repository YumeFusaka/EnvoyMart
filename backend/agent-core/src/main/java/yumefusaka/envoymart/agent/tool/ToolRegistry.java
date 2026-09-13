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
    private final ToolCallListener listener;

    public ToolRegistry() {
        this(ToolCallListener.NOOP);
    }

    public ToolRegistry(ToolCallListener listener) {
        this.listener = listener == null ? ToolCallListener.NOOP : listener;
    }

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
        long startedAt = System.nanoTime();
        ToolResult result = get(call.getToolName())
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

        // 三条来路（计划节点 / ReAct 循环 / MCP）都汇到这里，观测点放这儿才能全覆盖
        listener.onToolCall(call.getToolName(),
                result.isSuccess() ? ToolCallListener.Outcome.SUCCESS : ToolCallListener.Outcome.ERROR,
                (System.nanoTime() - startedAt) / 1_000_000);
        return result;
    }

    /**
     * 记录一次"被护栏拦下"的尝试。
     * <p>
     * 它没有进入 {@link #execute}，不产生任何下游调用，但确实消费了一次预算——
     * 不单独计数的话，就无从判断"预算是设得过紧"还是"模型真在失控"。
     */
    public void recordBlocked(String toolName) {
        listener.onToolCall(toolName, ToolCallListener.Outcome.BLOCKED, 0);
    }

    /** 列出所有需要用户确认的高危工具名。 */
    public List<String> confirmationRequiredTools() {
        return tools.values().stream()
                .filter(tool -> tool.getDefinition().isRequiresConfirmation())
                .map(tool -> tool.getDefinition().getName())
                .toList();
    }
}
