package yumefusaka.envoymart.agent.llm;

import java.util.List;

/**
 * 一次规划调用的完整产出：计划 + 本轮是否需要入口检索。
 * <p>
 * <b>为什么把「要不要检索」并进规划调用，而不是单独问一次模型</b>：
 * 单独分类要再花一次计费调用，而它想知道的信息（用户这句话要干什么）
 * 规划调用本来就已经在判断了。合并是在同一份输入上多要一个字段，
 * 代价是一次 JSON 字段，收益是省掉一整次往返。
 * <p>
 * {@code needRetrieval} 与计划是<b>两个独立维度</b>，不存在「有工具就不用检索」：
 * 用户问「这个能不能和钙片一起吃」，计划里可能一个工具都没有（答案在说明书里），
 * 却必须检索；而「帮我加购」有计划、却不需要先检索。
 *
 * @param plan          执行计划；空表示没有工具能帮上忙
 * @param needRetrieval 本轮是否需要先做一次入口检索把知识喂进上下文
 */
public record PlanWithIntent(List<PlanStep> plan, boolean needRetrieval) {

    /** 缺省：有工具就按老办法走（单测与降级路径用），不改变既有行为 */
    public static PlanWithIntent of(List<PlanStep> plan) {
        return new PlanWithIntent(plan, true);
    }
}