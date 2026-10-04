package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拒答门的判定逻辑。
 * <p>
 * 这道门的两侧代价不对称：<b>该拒没拒</b>会让模型拿不相关的切片编出一段带引用的回答，
 * 用户看到编号会以为已经核对过；<b>不该拒却拒了</b>只是多一轮「换个说法再问」。
 * 所以用例集中在「什么时候必须拒」，以及「信号缺失时不要误判成不相关」。
 */
class EvidenceGateTest {

    private static final EvidenceGate.Thresholds T = EvidenceGate.Thresholds.defaults();

    private DocumentChunk chunk(Double score, Boolean reranked) {
        return DocumentChunk.builder()
                .chunkId("c1").docId("d1").content("内容")
                .score(score).reranked(reranked)
                .build();
    }

    @Test
    void 没有召回任何切片时拒答() {
        assertThat(EvidenceGate.evaluate(List.of(), T).level()).isEqualTo(EvidenceGate.Level.NONE);
        assertThat(EvidenceGate.evaluate(null, T).level()).isEqualTo(EvidenceGate.Level.NONE);
    }

    @Test
    void 余弦相似度低于阈值判为证据不足() {
        var decision = EvidenceGate.evaluate(List.of(chunk(0.31, false)), T);

        assertThat(decision.level()).isEqualTo(EvidenceGate.Level.WEAK);
        assertThat(decision.topScore()).isEqualTo(0.31);
        assertThat(decision.reason()).contains("余弦相似度");
    }

    @Test
    void 余弦相似度达到阈值判为充分() {
        assertThat(EvidenceGate.evaluate(List.of(chunk(0.72, false)), T).level())
                .isEqualTo(EvidenceGate.Level.SUFFICIENT);
    }

    /**
     * 两把尺子必须分开用。
     * <p>
     * 0.25 在余弦尺度上是明显的弱相关，在 cross-encoder 尺度上却是可用的相关。
     * 用同一个阈值卡两条路径，等于让其中一条永远形同虚设。
     */
    @Test
    void 重排分与余弦相似度各用各的阈值() {
        assertThat(EvidenceGate.evaluate(List.of(chunk(0.25, false)), T).level())
                .as("0.25 的余弦相似度不够可信")
                .isEqualTo(EvidenceGate.Level.WEAK);
        assertThat(EvidenceGate.evaluate(List.of(chunk(0.25, true)), T).level())
                .as("0.25 的重排分已过阈值")
                .isEqualTo(EvidenceGate.Level.SUFFICIENT);
    }

    @Test
    void 判定看的是最高分而不是第一条() {
        var chunks = List.of(chunk(0.10, false), chunk(0.80, false), chunk(0.30, false));

        var decision = EvidenceGate.evaluate(chunks, T);

        assertThat(decision.level()).isEqualTo(EvidenceGate.Level.SUFFICIENT);
        assertThat(decision.topScore()).isEqualTo(0.80);
    }

    /**
     * 分数缺失 ≠ 不相关。
     * <p>
     * 纯关键词命中的切片没有相似度分，这是链路没给信号，不是「它不相关」的证据。
     * 因为指标缺失就拒答，是把工程缺陷转嫁给用户。
     */
    @Test
    void 完全没有相关性信号时放行而不是拒答() {
        var decision = EvidenceGate.evaluate(List.of(chunk(null, null), chunk(null, false)), T);

        assertThat(decision.level()).isEqualTo(EvidenceGate.Level.SUFFICIENT);
        assertThat(decision.topScore()).isNull();
        assertThat(decision.reason()).contains("未提供相关性分");
    }

    /** 部分切片有分、部分没有时，按有分的那条判，不能被 null 拉低 */
    @Test
    void 只有部分切片带分数时忽略无分的那条() {
        var decision = EvidenceGate.evaluate(List.of(chunk(null, null), chunk(0.66, false)), T);

        assertThat(decision.level()).isEqualTo(EvidenceGate.Level.SUFFICIENT);
        assertThat(decision.topScore()).isEqualTo(0.66);
    }

    private DocumentChunk graphChunk(Double score) {
        return DocumentChunk.builder()
                .chunkId("KB-0006_2").docId("KB-0006").title("深海鱼油软胶囊产品说明书")
                .source(DocumentChunk.SOURCE_GRAPH)
                .content("图谱推导：深海鱼油 人群禁忌 出血性疾病患者\n原文：出血性疾病患者……应咨询医师。")
                .score(score).reranked(Boolean.TRUE)
                .build();
    }

    /**
     * 图谱依据在场时，文本路的低分不再单独决定判定。
     * <p>
     * 实测到的失效：用户问「SPU7 有什么禁忌」，唯一正确的依据是图谱推出的
     * 「深海鱼油 → 人群禁忌 → 出血性疾病患者」，而它的重排分只有 0.13。
     * 跨编码器量的是<b>字面语义距离</b>，这两句话本来就不像 —— 拿它判「可不可信」，
     * 结论必然是模型答对了还要补一句「该条目不能作为权威依据」。
     */
    @Test
    void 图谱依据在场时不因文本分低而判为不足() {
        var chunks = List.of(chunk(0.13, true), graphChunk(0.13));

        var decision = EvidenceGate.evaluate(chunks, T);

        assertThat(decision.level()).isEqualTo(EvidenceGate.Level.SUFFICIENT);
        assertThat(decision.topScore())
                .as("分数照样如实报出来给日志，只是不再决定判定")
                .isEqualTo(0.13);
        assertThat(decision.reason()).contains("图谱依据在场");
    }

    /** 这条规则的另一半：没有图谱依据时，低分就是低分，别把口子开大 */
    @Test
    void 没有图谱依据时低分仍判为不足() {
        assertThat(EvidenceGate.evaluate(List.of(chunk(0.13, true)), T).level())
                .isEqualTo(EvidenceGate.Level.WEAK);
    }

    /**
     * 图谱依据只是「在场」还不够 —— 它必须是被重排器认可的那一条。
     * <p>
     * 实测到的反例（2026-10-03）：用户问「K2 和鱼油能一起吃吗」，图上没有 K2，
     * 实体链接只命中了鱼油，于是召回的全是鱼油的边。这些边因为带
     * {@code graphBacked} 标记，把整轮判定从 WEAK 抬成了 SUFFICIENT ——
     * <b>把「K2 未收录」说成了「有图谱依据」</b>。它们的重排分（0.13）明明低于阈值，
     * 说明重排器并不认为它们回答了用户的问题。
     * <p>
     * 判据因此收窄为：图谱豁免只在<b>图谱切片本身就是本轮最强的那条</b>时生效。
     * 图谱路的正文是「图谱推导：…」的转述，字面与问题不像，分数天然偏低 ——
     * 但只要它是被重排器挑出来的最强依据，就说明它确实对上了问题；
     * 反之，一条连自己都不是最强的图谱切片，没有资格替文本路背书。
     */
    @Test
    void 图谱切片不是最高分时不豁免() {
        var graph = graphChunk(0.13).toBuilder().graphBacked(true).build();
        var chunks = List.of(chunk(0.14, true), graph);

        var decision = EvidenceGate.evaluate(chunks, T);

        assertThat(decision.level())
                .as("图谱切片 0.13 低于文本切片 0.14 —— 它没有回答问题，不能替整轮背书")
                .isEqualTo(EvidenceGate.Level.WEAK);
    }

    /** 反过来：图谱切片确实是最强的那条时，豁免照常生效 */
    @Test
    void 图谱切片为最高分时仍豁免() {
        var graph = graphChunk(0.17).toBuilder().graphBacked(true).build();
        var chunks = List.of(chunk(0.13, true), graph);

        var decision = EvidenceGate.evaluate(chunks, T);

        assertThat(decision.level()).isEqualTo(EvidenceGate.Level.SUFFICIENT);
        assertThat(decision.reason()).contains("图谱依据在场");
    }

    /**
     * 真实链路里的形态：融合后留下的是<b>文本版</b>正文（source=manual），
     * 图谱身份只体现在 graphBacked 标记上 —— 这条路先前从未被测到过。
     */
    @Test
    void 文本版正文带图谱标记时也能触发豁免() {
        var textButGraphBacked = chunk(null, null).toBuilder()
                .source("manual").graphBacked(true)
                .score(0.17).reranked(true)
                .build();
        var chunks = List.of(chunk(0.13, true), textButGraphBacked);

        var decision = EvidenceGate.evaluate(chunks, T);

        assertThat(decision.reason())
                .as("source 是 manual，但图谱路确实召回并命中了这一片")
                .contains("图谱依据在场");
    }

    /**
     * U76 第三层：图谱切片被重排截断后，豁免必须靠<b>请求级事实</b>触发。
     * <p>
     * 实测形态（2026-10-03）：融合池 {@code candidates=9 kept=3}，图谱切片字面分低、
     * 排在 topK 之外被整条丢掉。此时 {@code chunks} 里根本没有图谱切片，
     * {@code topGraph} 必为 null —— 只看结果列表的话，豁免分支永远进不来，
     * <b>它不是没触发，是输入没送到。</b>
     */
    @Test
    void 图谱切片被重排截断时靠请求级事实豁免() {
        var chunks = List.of(chunk(0.13, true));

        var withoutFact = EvidenceGate.evaluate(chunks, T);
        var withFact = EvidenceGate.evaluate(chunks, T, java.util.Set.of("KB-0006_4"));

        assertThat(withoutFact.level())
                .as("没有请求级事实时，这就是一次普通的低分 —— 不能放行")
                .isEqualTo(EvidenceGate.Level.WEAK);
        assertThat(withFact.level())
                .as("图谱路本轮确实触达过依据，只是被 topK 截掉了")
                .isEqualTo(EvidenceGate.Level.SUFFICIENT);
        assertThat(withFact.reason()).contains("图谱依据在场");
    }

    /**
     * 请求级事实不能变成无条件放行 —— <b>K2 反例必须仍然判 WEAK。</b>
     * <p>
     * 用户问「K2 和鱼油能一起吃吗」，图上没有 K2，实体链接只命中鱼油，
     * 于是图谱路召回的全是鱼油的边。这些边<b>确实进了结果列表</b>
     * （不是被截掉的），{@code topGraph} 非 null 且分数低于文本路 ——
     * 它没有回答问题，不能替整轮背书。请求级事实在场也不该改变这个结论。
     */
    @Test
    void 图谱召回了但没回答问题时不因请求级事实而放行() {
        var graph = graphChunk(0.13).toBuilder().graphBacked(true).build();
        var chunks = List.of(chunk(0.14, true), graph);

        var decision = EvidenceGate.evaluate(chunks, T, java.util.Set.of("KB-0006_2"));

        assertThat(decision.level())
                .as("图谱切片就在结果里、且不是最强的那条 —— 它没回答问题")
                .isEqualTo(EvidenceGate.Level.WEAK);
    }
}
