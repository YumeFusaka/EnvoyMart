package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索评测器的文档级口径 —— 一次检索返回的是<b>切片</b>，指标问的是<b>文档</b>。
 * <p>
 * <b>钉住一个真实踩过的测量错误。</b>同一篇文档常有多个切片同时落进 top-K，
 * 若逐片计数，DCG 会把同一篇相关文档数两遍（NDCG 可以 &gt;1，我在生产语料上实测到 1.386），
 * 「命中排位」也会被同文档的重复项挤歪——一篇文档命中 3 次并不代表它排得更靠前，
 * 反而可能把另一篇真正相关的文档挤出 top-K，把一次本该命中的检索判成未命中。
 * <p>
 * 测量错误与产品 bug 一样会骗人，而且更难发现：数字看起来正常，只是不对。
 */
class RetrievalEvaluatorDedupTest {

    /** 前两片来自同一篇（A），第三片才是 B —— 去重后 A 应排第 1、B 排第 2 */
    private static final Retriever DUPLICATE_HEAVY = (query, topK) -> List.of(
            chunk("A", "c1"), chunk("A", "c2"), chunk("B", "c3"));

    private static DocumentChunk chunk(String docId, String chunkId) {
        return DocumentChunk.builder().docId(docId).chunkId(chunkId).content("x").build();
    }

    @Test
    void 同一文档的多个切片只算一次文档命中() {
        RetrievalEvaluator evaluator = new RetrievalEvaluator();
        List<RetrievalEvaluator.CaseOutcome> outcomes = evaluator.evaluateEach(
                DUPLICATE_HEAVY, List.of(new RetrievalEvaluator.EvalCase("q", List.of("A", "B"))), 3);

        RetrievalEvaluator.CaseOutcome outcome = outcomes.getFirst();
        assertThat(outcome.retrievedDocIds())
                .as("召回列表按文档去重，保序（首次出现为准）")
                .containsExactly("A", "B");
        assertThat(outcome.hitRank()).as("A 的排位是第 1，不是被重复项影响").isEqualTo(1);
        assertThat(outcome.ndcg())
                .as("NDCG 以文档为单位，绝不会超过 1")
                .isLessThanOrEqualTo(1.0);
    }

    @Test
    void 重复切片不会把真正相关的文档挤出文档级判据() {
        // 只有一片 C、而 A 占了两片：去重前 top-3 里 A、A、C 仍能命中；构造更极端的情形——
        // A 占满 top-K 时，若按片判定，C 就永远进不了判据。这里验证文档级判定不因重复而漏
        Retriever allSameDoc = (query, topK) -> List.of(chunk("A", "c1"), chunk("A", "c2"), chunk("A", "c3"));
        RetrievalEvaluator evaluator = new RetrievalEvaluator();
        RetrievalEvaluator.CaseOutcome outcome = evaluator.evaluateEach(
                allSameDoc, List.of(new RetrievalEvaluator.EvalCase("q", List.of("A"))), 3).getFirst();

        assertThat(outcome.retrievedDocIds()).containsExactly("A");
        assertThat(outcome.hit()).isTrue();
        assertThat(outcome.ndcg()).as("单文档、单命中，NDCG 应为 1").isEqualTo(1.0);
    }
}