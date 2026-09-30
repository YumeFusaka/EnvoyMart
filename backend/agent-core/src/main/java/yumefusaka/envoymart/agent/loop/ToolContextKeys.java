package yumefusaka.envoymart.agent.loop;

/**
 * 随工具调用传递的 per-request 上下文的键。
 * <p>
 * <b>三件本该由我们决定的事，靠前三个键送进工具循环：</b>循环边界、高危确认、
 * 调用者身份。它们的共同点是<b>都不能由模型提供</b>——模型既不知道真实用户是谁，
 * 也不该有权决定自己还能调用几次。放进 toolContext 由循环实现读取，
 * 使这些判断在模型可见的范围之外。
 * <p>
 * 第四个键方向相反（{@link #PENDING_APPROVAL}）：循环里拦下的高危操作靠它
 * 传回给调用方。进出都走这一个上下文，是因为循环的调用方与实现之间
 * 本就只有这一个 per-request 通道。
 */
public final class ToolContextKeys {

    /** {@link LoopGuard} 实例。循环实现每轮读它决定还下不下发工具定义 */
    public static final String LOOP_GUARD = "loopGuard";

    /** 用户是否已确认高危操作（Boolean） */
    public static final String APPROVED = "approved";

    /**
     * 经过认证的用户身份（String）。
     * <p>
     * 身份绝不能作为工具参数由模型提供——模型不知道真实用户是谁，只能编。
     * 缺失时（例如机器凭证调用）取身份的工具必须 fail-closed，而不是退化成"没有身份"。
     */
    public static final String USER_ID = "userId";

    /**
     * 待用户确认的高危操作描述（{@code List<String>}），形如 {@code order_cancel(orderId=12)}。
     * <p>
     * <b>这是这个上下文里唯一的出口键。</b>ReAct 循环在执行前拦下未确认的高危操作，
     * 把描述写进来并中断；调用方读它，决定要不要走确认中断出口（回答换成一句确认提示、
     * 附上确认卡片）。之所以需要它：计划路径能在执行前看到整份计划、提前拦，
     * ReAct 无从预知模型要调什么——只能在它调出来之后、执行之前拦，
     * 而拦住的结果必须有一条路回到循环外面。
     * <p>
     * 调用方不传也可以：循环会就地补一个本地列表，描述照样记录，只是没人读得到。
     */
    public static final String PENDING_APPROVAL = "pendingApproval";

    private ToolContextKeys() {
    }
}
