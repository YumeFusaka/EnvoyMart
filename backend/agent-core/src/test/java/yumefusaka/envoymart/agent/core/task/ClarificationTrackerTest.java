package yumefusaka.envoymart.agent.core.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多轮澄清收敛的契约。
 * <p>
 * 三条最要紧的断言，分别对应三种真实失效：
 * ① 澄清过的条件要记下来（否则会被反复问）；
 * ② **改口必须覆盖而不是叠加**（叠加会搜出空集，然后被说成「没有相关商品」）；
 * ③ 收敛判据不能只看轮数（问五轮拿到五个同义说法仍然选不出商品）。
 */
class ClarificationTrackerTest {

    @Test
    void 第一轮收集到症状但未收敛() {
        var progress = ClarificationTracker.advance(null, "最近便秘，排便困难");

        assertThat(progress.collected()).containsEntry(ClarificationTracker.SLOT_SYMPTOM, "便秘");
        assertThat(progress.converged()).isFalse();
        assertThat(progress.missing())
                .contains(ClarificationTracker.SLOT_POPULATION + "或" + ClarificationTracker.SLOT_CATEGORY);
        assertThat(progress.round()).isEqualTo(1);
    }

    /** 「最近」这类泛时间词不该进来当条件——它不改变候选集，只会在 prompt 里加噪音。 */
    @Test
    void 泛指时间词不作为条件收集() {
        var progress = ClarificationTracker.advance(null, "最近有点不舒服");

        assertThat(progress.collected())
                .as("「最近」太泛，几乎命中每一句症状描述，但对筛选商品毫无帮助")
                .doesNotContainKey(ClarificationTracker.SLOT_TIME);
    }

    @Test
    void 第二轮补齐品类后收敛() {
        var first = ClarificationTracker.advance(null, "最近有点便秘");
        var second = ClarificationTracker.advance(first, "想买益生菌，成人吃的");

        assertThat(second.collected())
                .containsEntry(ClarificationTracker.SLOT_SYMPTOM, "便秘")
                .containsEntry(ClarificationTracker.SLOT_CATEGORY, "益生菌")
                .containsEntry(ClarificationTracker.SLOT_POPULATION, "成人");
        assertThat(second.converged())
                .as("症状 + 品类/人群齐了就该给结论，不该再问")
                .isTrue();
        assertThat(second.missing()).isEmpty();
        assertThat(second.round()).isEqualTo(2);
    }

    /** 本类最重要的一条：改口要覆盖旧值，并且被显式记为一次纠正。 */
    @Test
    void 改口覆盖旧取值而不是叠加() {
        var first = ClarificationTracker.advance(null, "最近有点便秘");
        var second = ClarificationTracker.advance(first, "其实不是便秘，是腹泻");

        assertThat(second.collected().get(ClarificationTracker.SLOT_SYMPTOM))
                .as("两个互斥的说法同时当条件会搜出空集")
                .isEqualTo("腹泻");
        assertThat(second.corrections()).contains("症状：便秘 → 腹泻");
    }

    @Test
    void 非互斥的补充不算改口() {
        var first = ClarificationTracker.advance(null, "最近有点便秘");
        var second = ClarificationTracker.advance(first, "还有点腹胀");

        assertThat(second.corrections())
                .as("腹胀和便秘可以同时存在，这不是改口")
                .isEmpty();
    }

    @Test
    void 改口的那一轮不算收敛() {
        var first = ClarificationTracker.advance(null, "最近有点便秘，成人用的");
        var second = ClarificationTracker.advance(first, "其实不是便秘，是腹泻");

        assertThat(second.converged())
                .as("刚改过口就把结论定下来，很可能用了错的症状去过滤")
                .isFalse();
    }

    @Test
    void 抽不出条件时保持原样() {
        var first = ClarificationTracker.advance(null, "最近有点便秘");
        var second = ClarificationTracker.advance(first, "嗯嗯");

        assertThat(second.collected()).isEqualTo(first.collected());
        assertThat(second.round()).isEqualTo(2);
    }

    @Test
    void 未提及的槽位不会被清空() {
        var first = ClarificationTracker.advance(null, "便秘，成人用的");
        var second = ClarificationTracker.advance(first, "想买益生菌");

        assertThat(second.collected())
                .containsEntry(ClarificationTracker.SLOT_SYMPTOM, "便秘")
                .containsEntry(ClarificationTracker.SLOT_POPULATION, "成人")
                .containsEntry(ClarificationTracker.SLOT_CATEGORY, "益生菌");
    }
}
