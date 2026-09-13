package yumefusaka.envoymart.agent.tool;

/**
 * 工具调用监听 —— 让每一次工具调用都可被观测，无论它是谁发起的。
 * <p>
 * <b>为什么挂在这一层</b>：工具调用有三条来路——执行图的计划节点、框架驱动的 ReAct 循环、
 * 以及 MCP 外部客户端。三条路最终都汇到 {@link ToolRegistry#execute}，把观测点放在这里才能一处覆盖全部。
 * <p>
 * 早先埋点写在 `ToolRegistryToolCallback` 里，而计划路径根本不经过那个类——于是**主路径的调用一次都没被统计到**，
 * 指标看着有值却漏掉了大头。埋点位置错了，比没有埋点更有害：它让人以为自己在看全貌。
 */
@FunctionalInterface
public interface ToolCallListener {

    enum Outcome {
        SUCCESS,
        ERROR,
        /** 被循环护栏拦下——没有真正执行，但同样消费了一次预算，必须计入，否则无从判断预算是否过紧 */
        BLOCKED
    }

    /**
     * @param tool       工具名
     * @param outcome    结果分类
     * @param latencyMs  耗时；被拦截时为 0
     */
    void onToolCall(String tool, Outcome outcome, long latencyMs);

    /** 未接入观测时的空实现，保持 agent-core 零外部依赖。 */
    ToolCallListener NOOP = (tool, outcome, latencyMs) -> {
    };
}
