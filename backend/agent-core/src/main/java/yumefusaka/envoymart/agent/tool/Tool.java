package yumefusaka.envoymart.agent.tool;

/**
 * 可执行工具 —— 每个 Tool 实例封装一个具体能力。
 * <p>
 * 实现类同时通过 getDefinition() 暴露元数据，供 LLM 决策是否调用。
 */
public interface Tool {

    ToolDefinition getDefinition();

    ToolResult execute(ToolCall call);
}
