package yumefusaka.envoymart.agent.core.task;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.core.TaskStage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务状态机：合法流转、非法流转被拒、以及「不可变」这条性质。
 * <p>
 * 这些断言看着琐碎，但它们钉的是同一条不变契约——<b>恢复时读到的状态，
 * 一定是某一次成功迁移的产物，不会是一个从没被允许过的组合。</b>
 * 少了它，一次「DONE 又跳回 EXECUTING」的 bug 会静默通过，
 * 表现为恢复时该做的事没做，而日志里什么异常都没有。
 */
class TaskStateTest {

    private static final long T0 = 1_700_000_000_000L;

    private static TaskState fresh() {
        return TaskState.initial("u1:s1", "u1", "s1", T0);
    }

    @Test
    void 初始状态在规划阶段且没有待办() {
        TaskState state = fresh();

        assertThat(state.stage()).isEqualTo(TaskStage.PLANNING);
        assertThat(state.pendingTools()).isEmpty();
        assertThat(state.completedSteps()).isEmpty();
        assertThat(state.contextSnapshot()).isEmpty();
        assertThat(state.round()).isZero();
    }

    @Test
    void 正常主线一条路走通() {
        TaskState state = fresh()
                .withCoreIntent("查这单到哪了", T0)
                .withStage(TaskStage.EXECUTING, 1, T0)
                .withProgress("查物流", Map.of("orderId", 22L), T0)
                .withCompleted("logistics_query", T0)
                .withStage(TaskStage.CHECKING, 1, T0)
                .done(T0);

        assertThat(state.stage()).isEqualTo(TaskStage.DONE);
        assertThat(state.coreIntent()).isEqualTo("查这单到哪了");
        assertThat(state.completedSteps()).containsExactly("logistics_query");
        assertThat(state.pendingTools()).isEmpty();
    }

    @Test
    void 核对不满意可以回退重规划() {
        TaskState state = fresh().withStage(TaskStage.EXECUTING, 1, T0)
                .withStage(TaskStage.CHECKING, 1, T0)
                .withStage(TaskStage.PLANNING, 2, T0);

        assertThat(state.stage()).isEqualTo(TaskStage.PLANNING);
    }

    @Test
    void 中断等待后可以回到执行() {
        TaskState state = fresh().withStage(TaskStage.EXECUTING, 1, T0)
                .await(List.of("order_cancel"), 1, T0)
                .withStage(TaskStage.EXECUTING, 2, T0);

        assertThat(state.stage()).isEqualTo(TaskStage.EXECUTING);
        assertThat(state.round()).isEqualTo(2);
    }

    /**
     * 计划为空（直接对话、知识问答这类不需要工具的一轮）从规划直接收尾，
     * 是<b>最常见的正常路径</b>——它必须是合法边。
     * <p>
     * 把它判成非法的代价不是「挡住了一个 bug」，而是让合法边表的告警变成每轮必现：
     * 真正该被注意的那条不合法跳转会被淹没。判据要问「这条路真实会不会走」，
     * 而不是「它经不经过我设想的那几步」。
     */
    @Test
    void 计划为空时可以直接从规划收尾() {
        TaskState state = fresh().done(T0);

        assertThat(state.stage()).isEqualTo(TaskStage.DONE);
    }

    @Test
    void 非法流转被拒绝而不是静默忽略() {
        TaskState executing = fresh().withStage(TaskStage.EXECUTING, 1, T0);

        assertThatThrownBy(() -> executing.withStage(TaskStage.PLANNING, 1, T0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXECUTING")
                .hasMessageContaining("PLANNING");
    }

    @Test
    void 终态不可再迁移() {
        TaskState done = fresh().withStage(TaskStage.EXECUTING, 1, T0)
                .withStage(TaskStage.CHECKING, 1, T0).done(T0);

        assertThatThrownBy(() -> done.withStage(TaskStage.PLANNING, 2, T0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 迁移产出新实例不改原状态() {
        TaskState before = fresh().withCoreIntent("原意图", T0);
        TaskState after = before.withStage(TaskStage.EXECUTING, 1, T0);

        assertThat(before.stage()).isEqualTo(TaskStage.PLANNING);
        assertThat(after.stage()).isEqualTo(TaskStage.EXECUTING);
        assertThat(after.coreIntent()).isEqualTo("原意图");
    }

    @Test
    void 完成步骤去重且保序() {
        TaskState state = fresh().withCompleted("a", T0).withCompleted("b", T0).withCompleted("a", T0);

        assertThat(state.completedSteps()).containsExactly("a", "b");
    }

    @Test
    void 上下文快照允许null值且不可变() {
        Map<String, Object> raw = new java.util.LinkedHashMap<>();
        raw.put("orderId", 22L);
        raw.put("note", null);

        TaskState state = fresh().withProgress("查订单", raw, T0);

        assertThat(state.contextSnapshot()).containsEntry("orderId", 22L).containsKey("note");
        assertThatThrownBy(() -> state.contextSnapshot().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 合法边表可直接查询() {
        assertThat(TaskState.canTransition(TaskStage.PLANNING, TaskStage.EXECUTING)).isTrue();
        assertThat(TaskState.canTransition(TaskStage.EXECUTING, TaskStage.CHECKING)).isTrue();
        assertThat(TaskState.canTransition(TaskStage.PLANNING, TaskStage.DONE)).isTrue();
        assertThat(TaskState.canTransition(TaskStage.EXECUTING, TaskStage.PLANNING)).isFalse();
        assertThat(TaskState.canTransition(TaskStage.DONE, TaskStage.EXECUTING)).isFalse();
    }
}