package yumefusaka.envoymart.agent.loop;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 循环护栏 —— 两种循环（图里的环 + 框架驱动的工具循环）共用同一套预算。
 */
class LoopGuardTest {

    private static final Map<String, Object> ARGS = Map.of("orderId", 3);

    @Test
    void 工具调用总数超预算后拒绝执行() {
        LoopGuard guard = new LoopGuard(new LoopBudget(3, 99, 2));

        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isTrue();
        assertThat(guard.allowToolCall("b", Map.of("x", 1))).isTrue();
        assertThat(guard.allowToolCall("c", Map.of("x", 1))).isTrue();
        assertThat(guard.allowToolCall("d", Map.of("x", 1))).isFalse();

        assertThat(guard.getStopReason()).contains("工具调用总数");
    }

    @Test
    void 同一工具同一参数重复调用会被拦下() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 2, 2));

        assertThat(guard.allowToolCall("order_query", ARGS)).isTrue();
        assertThat(guard.allowToolCall("order_query", ARGS)).isTrue();
        // 第三次同样的调用 → 判定在原地打转
        assertThat(guard.allowToolCall("order_query", ARGS)).isFalse();
        assertThat(guard.getStopReason()).contains("重复调用");
    }

    @Test
    void 同一工具不同参数不算重复() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 2, 2));

        assertThat(guard.allowToolCall("order_query", Map.of("orderId", 1))).isTrue();
        assertThat(guard.allowToolCall("order_query", Map.of("orderId", 2))).isTrue();
        assertThat(guard.allowToolCall("order_query", Map.of("orderId", 3))).isTrue();

        assertThat(guard.isExhausted()).isFalse();
    }

    @Test
    void 参数顺序不影响重复判定() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 1, 2));

        assertThat(guard.allowToolCall("t", Map.of("a", 1, "b", 2))).isTrue();
        // 同样的参数、不同的 Map 顺序 → 仍应算作重复
        assertThat(guard.allowToolCall("t", Map.of("b", 2, "a", 1))).isFalse();
    }

    @Test
    void 规划轮次超限后不再重规划() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 2, 2));

        assertThat(guard.allowPlanRound()).isTrue();
        assertThat(guard.allowPlanRound()).isTrue();
        assertThat(guard.allowPlanRound()).isFalse();
        assertThat(guard.getStopReason()).contains("规划轮次");
    }

    @Test
    void 一旦触顶后续调用全部拒绝() {
        LoopGuard guard = new LoopGuard(new LoopBudget(1, 1, 1));

        assertThat(guard.allowToolCall("a", Map.of())).isTrue();
        assertThat(guard.allowToolCall("b", Map.of())).isFalse();
        // 已经停了，后续任何准入都不放行
        assertThat(guard.allowPlanRound()).isFalse();
    }

    @Test
    void 预算必须为正数() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new LoopBudget(0, 1, 1))).isNotNull();
    }
}
