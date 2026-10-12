package yumefusaka.envoymart.agent.tool;

/**
 * 可执行工具 —— 每个 Tool 实例封装一个具体能力。
 * <p>
 * 实现类同时通过 getDefinition() 暴露元数据，供 LLM 决策是否调用。
 */
public interface Tool {

    ToolDefinition getDefinition();

    ToolResult execute(ToolCall call);

    /** 只读查回同一签名操作的持久回执，禁止借恢复入口产生新副作用。 */
    default ToolResult recover(ToolCall call) {
        return ToolResult.builder().success(false).operationState(ToolResult.OperationState.NOT_FOUND)
                .errorMessage("该工具没有可查询的持久操作回执").build();
    }
}
