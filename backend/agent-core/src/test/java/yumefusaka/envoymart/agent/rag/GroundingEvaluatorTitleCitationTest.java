package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.GroundingEvaluator.CaseOutcome;
import yumefusaka.envoymart.agent.rag.GroundingEvaluator.Sample;
import yumefusaka.envoymart.agent.rag.GroundingFixtures.Kind;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测侧必须认《文档名》这种出处 —— 与生产同口径。
 * <p>
 * 病在「两把尺子」：线上把《文档名》当合法出处（见 {@code Agent#groundResponse}），
 * 而评测侧只数 {@code [n]} 角标。同一段回答，线上判它「有出处」，评测判它「整篇无依据」
 * ——报告页上的幻觉率会比用户实际看到的差，而且方向是<b>越用工具越差</b>：
 * 按需再检索这一批正把回答推向工具检索，工具切片没有编号可标，只能写出处。
 * <p>
 * 放宽必须只放宽到「平台声明过的标题」为止：模型自己编一个《野生维生素指南》，
 * 评测侧照样要把它按无依据计，否则这条口径就成了洗白通道。
 */
class GroundingEvaluatorTitleCitationTest {

    private static DocumentChunk doc(String docId, String title) {
        return DocumentChunk.builder()
                .chunkId(docId + "-1")
                .docId(docId)
                .title(title)
                .position("《" + title + "》 > 第二章 > 3.2")
                .content("成人每日推荐摄入量为 400IU，可耐受最高摄入量为 4000IU。")
                .source("manual")
                .version("v2026.03")
                .build();
    }

    private static Sample sample(Kind kind, String answer, List<DocumentChunk> evidence) {
        return new Sample("t-" + kind, kind, false, List.of("400IU"), evidence, answer,
                EvidenceGate.Level.SUFFICIENT, false);
    }

    private static final List<DocumentChunk> TWO_DOCS =
            List.of(doc("vitamin-d", "维生素D3说明书"), doc("calcium", "钙片说明书"));

    @Test
    void 引用平台文档名的句子不算无依据() {
        CaseOutcome outcome = GroundingEvaluator
                .evaluate(List.of(sample(Kind.ANSWERABLE,
                        "成人每日推荐摄入量为 400IU（《维生素D3说明书》）。", TWO_DOCS)))
                .cases().get(0);

        assertThat(outcome.mode())
                .as("按工具轮次处理：没有 [n] 可数，就不进引用准确率的分母")
                .isEqualTo(GroundingEvaluator.Mode.NOT_APPLICABLE);
        assertThat(outcome.unsupported())
                .as("线上判它有出处，评测就不能把同一句记成幻觉")
                .isZero();
    }

    @Test
    void 引用编造的文档名仍按整篇无依据计() {
        CaseOutcome outcome = GroundingEvaluator
                .evaluate(List.of(sample(Kind.ANSWERABLE,
                        "成人每日推荐摄入量为 400IU（《野生维生素指南》）。", TWO_DOCS)))
                .cases().get(0);

        assertThat(outcome.mode()).isEqualTo(GroundingEvaluator.Mode.WHOLE_UNGROUNDED);
        assertThat(outcome.unsupported()).isPositive();
    }

    @Test
    void 跨两篇文档的引用算多跳命中() {
        CaseOutcome outcome = GroundingEvaluator
                .evaluate(List.of(sample(Kind.MULTI_HOP,
                        "补钙看《钙片说明书》，补 D3 看《维生素D3说明书》，成人每日 400IU 即可。",
                        TWO_DOCS)))
                .cases().get(0);

        assertThat(outcome.multiHopHit())
                .as("两篇都引到了，只是写的是《文档名》而不是角标")
                .isTrue();
    }

    /** 同一篇文档两种写法混用不能被数成两篇 —— 否则多跳命中率会自己给自己发好成绩 */
    @Test
    void 同一篇文档的角标与书名号只算一篇() {
        CaseOutcome outcome = GroundingEvaluator
                .evaluate(List.of(sample(Kind.MULTI_HOP,
                        "补钙看《钙片说明书》[1]，D3 的摄入见 [1]（《维生素D3说明书》）。", TWO_DOCS)))
                .cases().get(0);

        assertThat(outcome.multiHopHit()).as("《维生素D3说明书》与 [1] 是同一篇").isFalse();
    }
}
