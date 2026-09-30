package yumefusaka.envoymart.agent.loop;

/**
 * 随工具调用传递的 per-request 上下文的键。
 * <p>
 * <b>两件本该由我们决定的事，靠前两个键送进工具循环：</b>循环边界与调用者身份。
 * 它们的共同点是<b>都不能由模型提供</b>——模型既不知道真实用户是谁，
 * 也不该有权决定自己还能调用几次。放进 toolContext 由循环实现读取，
 * 使这些判断在模型可见的范围之外。
 * <p>
 * 这里<b>没有</b>「用户已确认」这个键，是刻意的：确认不是循环的一个开关，
 * 而是一份绑定了具体动作的签名载荷（见 {@code ApprovalTokens}），
 * 由服务端在循环之外执行。高危工具因此永远不由模型驱动执行——
 * 循环里撞上它只有一条路：拦下、记进 {@link #PENDING_ACTIONS}、中断。
 * <p>
 * 第三个键方向相反（{@link #PENDING_ACTIONS}）：循环里拦下的高危操作靠它
 * 传回给调用方。进出都走这一个上下文，是因为循环的调用方与实现之间
 * 本就只有这一个 per-request 通道。
 */
public final class ToolContextKeys {

    /** {@link LoopGuard} 实例。循环实现每轮读它决定还下不下发工具定义 */
    public static final String LOOP_GUARD = "loopGuard";

    /**
     * 经过认证的用户身份（String）。
     * <p>
     * 身份绝不能作为工具参数由模型提供——模型不知道真实用户是谁，只能编。
     * 缺失时（例如机器凭证调用）取身份的工具必须 fail-closed，而不是退化成"没有身份"。
     */
    public static final String USER_ID = "userId";

    /**
     * 拦下的高危操作（{@code List<PendingAction>}）——<b>结构化的工具名与入参，
     * 不是一句可读描述。</b>
     * <p>
     * <b>这是这个上下文里唯一的出口键。</b>ReAct 循环在执行前拦下高危操作，
     * 把调用本身写进来并中断；调用方读它，签发确认令牌、渲染确认卡片。
     * 之所以需要它：计划路径能在执行前看到整份计划、提前拦，
     * ReAct 无从预知模型要调什么——只能在它调出来之后、执行之前拦，
     * 而拦住的结果必须有一条路回到循环外面。
     * <p>
     * <b>为什么是结构化类型而不是描述文本</b>：描述是给用户看的，执行要的是载荷。
     * 只把描述带出去的话，确认轮就只剩「模型凭记忆重放一遍」这条路——
     * 而用户批准的是他看见的那一次调用，不是下一次模型想出什么。
     * <p>
     * 调用方不传也可以：循环会就地补一个本地列表，拦截照样发生，只是没人读得到。
     */
    public static final String PENDING_ACTIONS = "pendingActions";

    private ToolContextKeys() {
    }
}
