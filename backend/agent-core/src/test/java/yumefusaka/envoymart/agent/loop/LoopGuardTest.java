package yumefusaka.envoymart.agent.loop;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
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

    // ---------- 规范化签名（U11） ----------

    @Test
    void 嵌套Map不同键序判为同一次调用() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 1, 2));

        Map<String, Object> inner1 = new LinkedHashMap<>();
        inner1.put("x", 1);
        inner1.put("y", 2);
        Map<String, Object> inner2 = new LinkedHashMap<>();
        inner2.put("y", 2);
        inner2.put("x", 1);

        assertThat(guard.allowToolCall("t", Map.of("nested", inner1))).isTrue();
        // 同一份嵌套参数、只是内部键序不同 → 必须算同一次，第二次即触顶
        assertThat(guard.allowToolCall("t", Map.of("nested", inner2))).isFalse();
        assertThat(guard.getStopReason()).contains("重复调用");
    }

    @Test
    void List顺序敏感但内容相同才算同一次() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 1, 2));

        assertThat(guard.allowToolCall("t", Map.of("ids", List.of(1, 2)))).isTrue();
        // 列表是「有序值」，[1,2] 与 [2,1] 语义不同 → 不该算重复
        assertThat(guard.allowToolCall("t", Map.of("ids", List.of(2, 1)))).isTrue();
        guard = new LoopGuard(new LoopBudget(99, 1, 2));
        assertThat(guard.allowToolCall("t", Map.of("ids", List.of(1, 2)))).isTrue();
        assertThat(guard.allowToolCall("t", Map.of("ids", List.of(1, 2)))).isFalse();
    }

    @Test
    void 数字类型归一后判为同一次调用() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 1, 2));

        // 同一个数值 3，以 Integer / Long / Double 三种装箱进来 → 应算同一次
        assertThat(guard.allowToolCall("t", Map.of("id", 3))).isTrue();
        assertThat(guard.allowToolCall("t", Map.of("id", 3L))).isFalse();
        assertThat(guard.getStopReason()).contains("重复调用");

        guard = new LoopGuard(new LoopBudget(99, 1, 2));
        assertThat(guard.allowToolCall("t", Map.of("id", 3))).isTrue();
        assertThat(guard.allowToolCall("t", Map.of("id", 3.0))).isFalse();
    }

    @Test
    void 数字与同形字符串不判为同一次调用() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 1, 2));

        // 3 与 "3" 是两种类型，合并会让「按 id 查」与「按名查」互相算重复，故保持区分
        assertThat(guard.allowToolCall("t", Map.of("id", 3))).isTrue();
        assertThat(guard.allowToolCall("t", Map.of("id", "3"))).isTrue();
        assertThat(guard.isExhausted()).isFalse();
    }

    @Test
    void 空Map与null参数不判为同一次调用() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 1, 2));

        assertThat(guard.allowToolCall("t", Map.of())).isTrue();
        assertThat(guard.allowToolCall("t", null)).isTrue();
        assertThat(guard.isExhausted()).isFalse();
    }

    @Test
    void 缺字段与字段为null不判为同一次调用() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 1, 2));

        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("a", 1);
        withNull.put("b", null);

        assertThat(guard.allowToolCall("t", Map.of("a", 1))).isTrue();
        assertThat(guard.allowToolCall("t", withNull)).isTrue();
        assertThat(guard.isExhausted()).isFalse();
    }

    // ---------- 交替震荡（重点方向⑥） ----------

    @Test
    void 交替往复调用会被拦下() {
        // 总预算与同参重复都放到很宽，只留震荡这一条能拦住
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 99, 2));

        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isTrue();
        assertThat(guard.allowToolCall("b", Map.of("y", 2))).isTrue();
        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isTrue();
        // 第 4 次构成第二次 A,B,A,B：默认上限 2 时第 2 次往复放行
        assertThat(guard.allowToolCall("b", Map.of("y", 2))).isTrue();
        // 第 5、6 次构成第三次往复（相邻对出现 3 次）→ 第 6 次拦下
        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isTrue();
        assertThat(guard.allowToolCall("b", Map.of("y", 2))).isFalse();

        assertThat(guard.getStopReason()).contains("往复");
    }

    @Test
    void 震荡上限为一表示允许一次往复() {
        // 上限 1 表示「允许一组相邻对出现一次」：A,B,A（一对）放行，第二次 A,B 才停
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 99, 2, 1));

        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isTrue();
        assertThat(guard.allowToolCall("b", Map.of("y", 2))).isTrue();
        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isTrue();
        // 第二对 (a,b) 出现 → maxPair=2 > 1，拦下
        assertThat(guard.allowToolCall("b", Map.of("y", 2))).isFalse();
        assertThat(guard.getStopReason()).contains("往复");
    }

    @Test
    void 震荡上限为零时关闭震荡检测() {
        // 0 的语义是「关闭震荡检测」，交由总预算与同参重复兜底；
        // 所以这里 A,B,A,B,A,B 不该被震荡拦下
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 99, 2, 0));

        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isTrue();
        assertThat(guard.allowToolCall("b", Map.of("y", 2))).isTrue();
        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isTrue();
        assertThat(guard.allowToolCall("b", Map.of("y", 2))).isTrue();
        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isTrue();
        assertThat(guard.allowToolCall("b", Map.of("y", 2))).isTrue();
        assertThat(guard.isExhausted()).isFalse();
    }

    @Test
    void 单次交叉A_B_A不算震荡() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 99, 2));

        // 只有一对相邻对 (A,B)，不到两次 → 不判震荡
        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isTrue();
        assertThat(guard.allowToolCall("b", Map.of("y", 2))).isTrue();
        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isTrue();
        // 但 A 已第三次 → 由同参重复拦下（本用例的下限保护：不是被误判成震荡）
        assertThat(guard.allowToolCall("b", Map.of("y", 2))).isTrue();
        assertThat(guard.getStopReason()).isNull();
    }

    @Test
    void 同工具换参数不误判为震荡() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 2, 2));

        // 同一工具换参数属于正常推进，不该被判成往复
        assertThat(guard.allowToolCall("order_query", Map.of("orderId", 1))).isTrue();
        assertThat(guard.allowToolCall("order_query", Map.of("orderId", 2))).isTrue();
        assertThat(guard.allowToolCall("order_query", Map.of("orderId", 3))).isTrue();
        assertThat(guard.allowToolCall("order_query", Map.of("orderId", 4))).isTrue();
        assertThat(guard.isExhausted()).isFalse();
    }

    @Test
    void 两个工具交替但参数不同不误判为震荡() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 2, 2));

        // 同样是 a/b 交替，但每次参数都不同 —— 语义不等价，不该算同一个 op
        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isTrue();
        assertThat(guard.allowToolCall("b", Map.of("y", 1))).isTrue();
        assertThat(guard.allowToolCall("a", Map.of("x", 2))).isTrue();
        assertThat(guard.allowToolCall("b", Map.of("y", 2))).isTrue();
        assertThat(guard.allowToolCall("a", Map.of("x", 3))).isTrue();
        assertThat(guard.allowToolCall("b", Map.of("y", 3))).isTrue();
        assertThat(guard.isExhausted()).isFalse();
    }

    @Test
    void 三工具轮转不判为震荡() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 2, 2));

        // A→B→C→A→B→C 是「相邻都不同、跨一格也不同」，不满足震荡判据
        assertThat(guard.allowToolCall("a", Map.of("i", 1))).isTrue();
        assertThat(guard.allowToolCall("b", Map.of("i", 1))).isTrue();
        assertThat(guard.allowToolCall("c", Map.of("i", 1))).isTrue();
        assertThat(guard.allowToolCall("a", Map.of("i", 1))).isTrue();
        assertThat(guard.allowToolCall("b", Map.of("i", 1))).isTrue();
        assertThat(guard.allowToolCall("c", Map.of("i", 1))).isTrue();
        assertThat(guard.isExhausted()).isFalse();
    }

    @Test
    void 重复判定优先于震荡判定() {
        // 同参上限 2：A,A,A 第三次应先报「重复调用」，而不是被震荡掩盖
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 2, 2));

        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isTrue();
        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isTrue();
        assertThat(guard.allowToolCall("a", Map.of("x", 1))).isFalse();
        assertThat(guard.getStopReason()).contains("重复调用");
    }

    @Test
    void 四参数构造校验震荡上限非负() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new LoopBudget(8, 2, 2, -1))).isNotNull();
    }

    @Test
    void 摘要包含震荡计数() {
        LoopGuard guard = new LoopGuard(new LoopBudget(99, 99, 2));
        guard.allowToolCall("a", Map.of("x", 1));
        guard.allowToolCall("b", Map.of("y", 2));

        assertThat(guard.summary()).contains("oscillations=");
    }
}
