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
 */
public record LoopBudget(int maxToolCalls, int maxRepeatedAction, int maxPlanRounds) {

    public static LoopBudget defaults() {
        return new LoopBudget(8, 2, 2);
    }

    public LoopBudget {
        if (maxToolCalls < 1 || maxRepeatedAction < 1 || maxPlanRounds < 1) {
            throw new IllegalArgumentException("循环预算必须为正数");
        }
    }
}
