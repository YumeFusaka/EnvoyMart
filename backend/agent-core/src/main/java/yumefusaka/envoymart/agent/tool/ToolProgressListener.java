package yumefusaka.envoymart.agent.tool;

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
