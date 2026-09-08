package yumefusaka.envoymart.agent.loop;

/**
 * 随工具调用传递的 per-request 上下文的键。
 * <p>
 * 工具循环由框架驱动，这些值是我们在调用模型时放进 toolContext、
 * 在 {@code ToolCallback} 里取出来的——用来把"循环边界"和"高危确认"
 * 这两件本该由我们决定的事，接进框架的循环里。
 */
public final class ToolContextKeys {

    /** {@link LoopGuard} 实例 */
    public static final String LOOP_GUARD = "loopGuard";

    /** 用户是否已确认高危操作（Boolean） */
    public static final String APPROVED = "approved";

    /**
     * 经过认证的用户身份（String）。
     * <p>
     * 与 {@code LOOP_GUARD}、{@code APPROVED} 同类：由我们把关的事，经 toolContext 送进
     * 框架驱动的工具调用点。身份绝不能作为工具参数由模型提供——模型不知道真实用户是谁。
     */
    public static final String USER_ID = "userId";

    private ToolContextKeys() {
    }
}
