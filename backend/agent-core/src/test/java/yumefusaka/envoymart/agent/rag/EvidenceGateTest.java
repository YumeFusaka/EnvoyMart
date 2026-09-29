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
}
