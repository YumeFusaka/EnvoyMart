package yumefusaka.envoymart.agent.loop;

/**
 * 单次请求的循环预算。
 * <p>
 * Agent 的不确定性主要来自"循环可能不收敛"：模型反复调工具、反复重规划。
 * 把边界显式化，比事后调大框架的默认上限更可控。
 *
 * @param maxToolCalls       单次请求允许的工具调用总数
 * @param maxRepeatedAction  同一「工具 + 参数」允许重复的次数
 * @param maxPlanRounds      规划轮次上限（含首次规划与重规划）
 * @param maxOscillations    允许出现的「往复调用」次数上限。见 {@link LoopGuard}：总预算与
 *                           同参重复都拦不住 A→B→A→B 这种交替打转，这条是它的专属闸门
 */
public record LoopBudget(int maxToolCalls, int maxRepeatedAction, int maxPlanRounds, int maxOscillations) {

    /**
     * 旧三参数构造 —— 保留它是因为「震荡检测」是新增能力，不是既有预算的改变。
     * 三参数的调用点拿到的行为应与改造前逐位一致（震荡上限取默认值 2 属于新增判定，
     * 语义上等价于「按新规矩允许两次往复」，不改变原有四条预算的数值）。
     */
    public LoopBudget(int maxToolCalls, int maxRepeatedAction, int maxPlanRounds) {
        this(maxToolCalls, maxRepeatedAction, maxPlanRounds, DEFAULT_MAX_OSCILLATIONS);
    }

    public static final int DEFAULT_MAX_OSCILLATIONS = 2;

    public static LoopBudget defaults() {
        return new LoopBudget(8, 2, 2, DEFAULT_MAX_OSCILLATIONS);
    }

    public LoopBudget {
        if (maxToolCalls < 1 || maxRepeatedAction < 1 || maxPlanRounds < 1 || maxOscillations < 0) {
            throw new IllegalArgumentException("循环预算必须为正数");
        }
    }
}
