package yumefusaka.envoymart.agent.flow;

/**
 * 确定性流程 —— 路径与判定都由代码决定，不交给模型即兴发挥。
 * <p>
 * 与「让模型自由推理」的区别不在能力，而在**控制权**：
 * <ul>
 *   <li>普通工具：执行权归代码，<b>路由权归模型</b>（模型决定要不要调）；</li>
 *   <li>确定性流程：<b>路由权与执行权都归代码</b>（判定条件命中就一定走）。</li>
 * </ul>
 * 适用于规则明确、步骤固定、不能出错、且要求可复现的场景（如售后资格判定）。
 * <p>
 * 注意：<b>业务判定必须由代码做</b>（订单状态决定能不能退），
 * 但「这条消息该不该走本流程」这类语义判断可以交给模型——见 {@link IntentRouter}。
 */
public interface DeterministicFlow {

    String getName();

    /** 供意图路由使用的自然语言描述，模型据此判断该不该走这条流程。 */
    String getDescription();

    /**
     * 规则判定 —— 覆盖「规则能判定的全部条件」：意图词 + 参数齐备性。
     * <p>
     * 两个用途：
     * <ul>
     *   <li>模型可用时：{@link IntentRouter} 先用模型判断意图，再用本方法<b>确认参数齐备</b>
     *       （模型可能忽略了"没给订单号"这种事）；</li>
     *   <li>模型不可用时：本方法是唯一的降级判断。</li>
     * </ul>
     * 设计原则是<b>高精度、低召回</b>：误判会让用户拿到答非所问的结果且没有兜底，
     * 漏判还有 Agent 编排接着（最多多问一句）。所以拿不准就不接管。
     */
    boolean matches(String userMessage);

    FlowResult execute(FlowContext context);
}
