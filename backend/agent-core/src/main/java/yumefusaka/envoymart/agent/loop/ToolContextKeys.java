package yumefusaka.envoymart.agent.loop;

/**
 * 随工具调用传递的 per-request 上下文的键。
 * <p>
 * <b>三件本该由我们决定的事，靠这三个键接进工具循环：</b>循环边界、高危确认、
 * 调用者身份。它们的共同点是<b>都不能由模型提供</b>——模型既不知道真实用户是谁，
 * 也不该有权决定自己还能调用几次。放进 toolContext 由循环实现读取，
 * 使这些判断在模型可见的范围之外。
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

    private ToolContextKeys() {
    }
}
