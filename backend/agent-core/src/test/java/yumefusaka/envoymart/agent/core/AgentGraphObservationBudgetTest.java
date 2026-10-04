package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具观测的总长度上界。
 * <p>
 * 守的是一条此前不存在的约束：单条工具有 {@code ToolRegistry} 的 4000 字符截断，
 * 但**多条合计没有上界** —— 一次请求最多 8 次工具调用，最坏 32000 字符一起进 prompt，
 * 而这段完全不在 {@code ContextBudget}（它只裁历史）的账上。
 * <p>
 * 因此这里要验三件事：总量确实被压在上界内、超限时明确标注省略了几条、
 * 以及没超限时一个字都不许改（不能为了「保险」把所有正常请求也截一遍）。
 */
class AgentGraphObservationBudgetTest {

    private static AgentGraph.GraphStep step(String tool, String output) {
        return AgentGraph.GraphStep.builder().tool(tool).output(output).success(true).build();
    }

    @Test
    void 正常规模的观测原样保留不截断() {
        List<AgentGraph.GraphStep> steps = List.of(
                step("order_query", "订单号 OM001，状态 已发货，金额 128 元"),
                step("logistics_track", "最新轨迹：已到达上海分拨中心"));

        String rendered = AgentGraph.renderObservations(steps);

        assertThat(rendered).contains("订单号 OM001").contains("上海分拨中心");
        assertThat(rendered).doesNotContain("超限").doesNotContain("未展示");
    }

    @Test
    void 总量超限时被压在上界附近且标注省略条数() {
        // 8 条 × 4000 字符，正是「每条都顶到单条上限」的最坏形态
        List<AgentGraph.GraphStep> steps = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            steps.add(step("tool_" + i, "x".repeat(4000)));
        }

        String rendered = AgentGraph.renderObservations(steps);

        // 允许一点余量给「本条因观测总量超限被截断」这类标记文字，但绝不能是 32000
        assertThat(rendered.length()).isLessThan(13000);
        assertThat(rendered).contains("未展示");
    }

    @Test
    void 超限时头部工具的结果优先保留() {
        List<AgentGraph.GraphStep> steps = new ArrayList<>();
        steps.add(step("key_tool", "关键第一手结果 订单号 OM999"));
        for (int i = 0; i < 7; i++) {
            steps.add(step("filler_" + i, "y".repeat(4000)));
        }

        String rendered = AgentGraph.renderObservations(steps);

        // 先到的观测是最靠近用户问题的那批查询，截断必须从尾部开始
        assertThat(rendered).contains("OM999");
    }
}
