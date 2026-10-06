package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冲突裁决第一判据的行为锁定。
 * <p>
 * 这张表的语义很容易在后续维护里被"顺手改坏"——最危险的一种改法是
 * 把 {@link SourceAuthority#dominates} 改成「不相等就分高下」，
 * 那会让系统在一个它其实没有依据的问题上（两份同为厂商说明书）给出斩钉截铁的裁决。
 */
class SourceAuthorityTest {

    @Test
    void 监管规范压过厂商说明书() {
        assertThat(SourceAuthority.dominates("regulation", "manual"))
                .contains(SourceAuthority.REGULATION);
        assertThat(SourceAuthority.dominates("manual", "regulation"))
                .as("方向反过来也要认，不能只看参数顺序")
                .isEmpty();
    }

    @Test
    void 平台政策压过说明书与技术规格() {
        assertThat(SourceAuthority.dominates("policy", "manual")).contains(SourceAuthority.POLICY);
        assertThat(SourceAuthority.dominates("policy", "spec")).contains(SourceAuthority.POLICY);
    }

    @Test
    void 同档分不出高下_必须让判据继续往下走() {
        // 同为厂商材料的两份文档，谁更可信取决于内容与版本，不取决于标签。
        // 这里若给出一个"赢家"，系统就会替用户赌一把，而它其实没有依据
        assertThat(SourceAuthority.dominates("manual", "manual")).isEmpty();
        assertThat(SourceAuthority.dominates("policy", "policy")).isEmpty();
    }

    @Test
    void 未知来源落到最低档_方向是保守的() {
        // 笔误的来源（mannual）不该看起来和正牌说明书一样可信；
        // 落最低档意味着它盖不过任何人，错了也只是裁决偏保守
        assertThat(SourceAuthority.of("mannual")).isEqualTo(SourceAuthority.GUIDE);
        assertThat(SourceAuthority.of(null)).isEqualTo(SourceAuthority.GUIDE);
        assertThat(SourceAuthority.of("  REGULATION  "))
                .as("大小写与空白不该影响解析")
                .isEqualTo(SourceAuthority.REGULATION);
    }

    @Test
    void 词表把全部来源都列出来供提示词使用() {
        String vocab = SourceAuthority.vocab();
        assertThat(vocab)
                .as("提示词靠这一段告诉模型判据存在，漏掉一档模型就少一个可用依据")
                .contains("监管规范(regulation)")
                .contains("厂商说明书(manual)")
                .contains("参考资料(guide)");
    }
}
