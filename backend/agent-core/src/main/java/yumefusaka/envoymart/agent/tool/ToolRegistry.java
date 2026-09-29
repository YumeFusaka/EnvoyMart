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
                    ToolResult missing = missingRequired(tool.getDefinition(), call);
                    if (missing != null) {
                        return missing;
                    }
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
     * 必填参数校验：缺了就<b>当场说清缺哪个、实际收到什么</b>，而不是放进去让工具自己炸。
     * <p>
     * <b>模型的参数键是不可信输入。</b>计划路径的 arguments 由模型生成，实测会把
     * {@code orderId} 写成 {@code order_id}（提示词当时没列参数名，它只能猜）。
     * 工具按声明名取值取到 {@code null}，抛出来是一句 {@code NullPointerException}——
     * 既不指向原因，也不指向修法，只能靠人去读工具源码才明白是键名错了。
     * 这里挡一道，报错里同时给出「缺哪个」和「你给的是哪个」，一眼就能分清是键名写错还是漏传。
     * <p>
     * <b>校验放在这里而不是每个工具自己写</b>：计划节点、ReAct 循环、MCP 三条来路都汇到
     * {@link #execute}，写一次三处都有；散到各工具里则必然有的写了有的没写。
     * <p>
     * 只校验「必填项在不在」，不校验类型与取值——那是工具自己的事，注册中心不该越界。
     */
    private static ToolResult missingRequired(ToolDefinition definition, ToolCall call) {
        Map<String, ToolDefinition.ParameterSpec> declared = definition.getParameters();
        if (declared == null || declared.isEmpty()) {
            return null;
        }
        Map<String, Object> arguments = call.getArguments() == null ? Map.of() : call.getArguments();
        List<String> missing = declared.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().isRequired() && arguments.get(e.getKey()) == null)
                .map(Map.Entry::getKey)
                .toList();
        if (missing.isEmpty()) {
            return null;
        }
        return ToolResult.builder()
                .success(false)
                .errorMessage("缺少必填参数 " + String.join("、", missing)
                        + "；实际收到的参数是 " + (arguments.isEmpty() ? "（无）" : arguments.keySet()))
                .build();
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
