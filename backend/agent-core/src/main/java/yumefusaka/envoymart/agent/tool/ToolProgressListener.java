package yumefusaka.envoymart.agent.tool;

import yumefusaka.envoymart.agent.core.AgentCancelledException;

/**
 * 工具执行的实时进度回调（请求级）。
 * <p>
 * 与 {@link ToolCallListener} 的区别是<b>方向与时刻</b>：那个是执行<b>结束后</b>的观测
 * （进指标、按工具名聚合，单例即可）；这个是执行<b>前后</b>的实时通知，服务于
 * 「流式对话期间让用户看到 agent 正在做什么」——工具编排阶段用户看到的不再是
 * 一段静止的「正在生成」，而是「正在查询商品」这样的中间步骤。
 * <p>
 * <b>必须是请求级实例。</b>事件最终要落到发起这次对话的那条 SSE 连接上，
 * 做成单例就会被并发请求共享，A 用户会看到 B 用户的工具进度。
 * <p>
 * 注意这<b>不是</b>「用户已确认」的开关，与 {@link ToolCallListener} 同样不参与放行判断：
 * 它只是一个旁路通知，接了也改变不了执行与否。被护栏拦下或被高危闸口拦下的调用
 * <b>不发事件</b>——那两种情况下工具没有真正执行，界面不该出现「正在执行」。
 */
public interface ToolProgressListener {

    /** 即将执行（护栏已放行、高危检查已通过）。 */
    void onStart(String tool);

    /**
     * 执行结束。
     *
     * @param noData 只对成功有意义：查空是有效答案，界面标「无结果」而非「失败」
     */
    void onFinish(String tool, boolean success, boolean noData, long latencyMs);

    /**
     * 本轮对话是否已被取消（用户点了「停止生成」，或直接断开连接）。
     * <p>
     * 取消信号搭这条通道下发，因为它已经是执行链上唯一贯穿全程的请求级对象：
     * 计划路径、ReAct 循环、确认轮都拿着它。为此再加一个参数，等于把
     * 「进度就在听、取消没在听」这种不一致留给下一个调用点去犯错。
     * <p>
     * 默认 false：非流式入口、MCP 路径与测试里的监听器没有取消这个概念，
     * 新增方法不能逼它们全部实现一遍。
     */
    default boolean cancelled() {
        return false;
    }

    /**
     * 取消中就直接中断——在「即将开始新工作」的位置调用。
     * <p>
     * 只查不抛、让每个调用点自己写 if，迟早会漏；收成一个方法，
     * 语义（<b>不再开始</b>，而非打断进行中的调用）与异常类型都只有一处。
     */
    default void throwIfCancelled() {
        if (cancelled()) {
            throw new AgentCancelledException("本轮对话已被取消");
        }
    }

    /** 未接入实时界面时的空实现（非流式入口、测试）。 */
    ToolProgressListener NOOP = new ToolProgressListener() {
        @Override
        public void onStart(String tool) {
        }

        @Override
        public void onFinish(String tool, boolean success, boolean noData, long latencyMs) {
        }
    };

    /** null 安全包装：调用方漏传时退化为 NOOP，而不是在工具执行处抛 NPE。 */
    static ToolProgressListener orNoop(ToolProgressListener listener) {
        return listener == null ? NOOP : listener;
    }
}
