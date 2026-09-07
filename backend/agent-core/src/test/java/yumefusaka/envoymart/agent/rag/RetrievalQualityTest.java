package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索质量回归 —— 用标注样本锁住召回与排序，防止改动把效果悄悄改坏。
 * <p>
 * 语料与样本见 {@link RetrievalFixtures}。这里只跑 BM25 路（向量库留空），
 * 因此指标反映的是<b>关键词路的底线</b>，用于防退化；
 * 接入真实向量与重排后的对照见 {@link RetrievalComparisonTest}。
 */
class RetrievalQualityTest {

    private static final int TOP_K = 3;

    @Test
    void 关键词检索在字面重合的查询上表现稳定() {
        RetrievalEvaluator.EvalReport report = evaluate(RetrievalFixtures.LEXICAL_CASES);

        System.out.println("[检索评测-字面] " + report);

        assertThat(report.hitRate()).isGreaterThanOrEqualTo(0.8);
        assertThat(report.mrr()).isGreaterThanOrEqualTo(0.7);
    }

    @Test
    void 口语化改写与语义鸿沟查询会掉分_用于对比引入向量与重排的收益() {
        RetrievalEvaluator.EvalReport paraphrase = evaluate(RetrievalFixtures.PARAPHRASE_CASES);
        RetrievalEvaluator.EvalReport hard = evaluate(RetrievalFixtures.HARD_CASES);

        System.out.println("[检索评测-口语] " + paraphrase);
        System.out.println("[检索评测-难例] " + hard);

        // 这组不设高门槛：它的价值是暴露关键词检索的短板，不是刷分
        assertThat(paraphrase.caseCount()).isEqualTo(RetrievalFixtures.PARAPHRASE_CASES.size());
        assertThat(hard.caseCount()).isEqualTo(RetrievalFixtures.HARD_CASES.size());
    }

    /**
     * 全量指标与回归门槛 —— 文档引用的就是这一组数字。
     * <p>
     * 门槛设在实测值之下留出余量，作用是回归防线：改动把关键词路改坏时报错。
     * 注意这只测了关键词路的底线；接入向量与重排后整体指标会高于此。
     */
    @Test
    void 全量评测指标不低于回归门槛() {
        RetrievalEvaluator.EvalReport report = evaluate(RetrievalFixtures.allCases());

        System.out.println("[检索评测-全量] " + report);
        System.out.printf("[随机基线] corpus=%d topK=%d hitRate=%.3f%n",
                RetrievalFixtures.DOCS.size(), TOP_K,
                RetrievalFixtures.randomBaselineHitRate(RetrievalFixtures.DOCS.size(), TOP_K));

        assertThat(report.caseCount()).isEqualTo(30);
        assertThat(report.hitRate()).isGreaterThanOrEqualTo(0.6);
        assertThat(report.mrr()).isGreaterThanOrEqualTo(0.58);
        assertThat(report.ndcg()).isGreaterThanOrEqualTo(0.55);
    }

    @Test
    void 重排器可以改变最终排序() {
        Retriever retriever = new HybridRetriever(
                new InMemoryVectorStore(new SimpleEmbeddingService()), RetrievalFixtures.DOCS,
                // 假重排：把含"发票"的候选顶到第一位，验证重排确实生效
                (query, candidates, topK) -> candidates.stream()
                        .sorted((a, b) -> Boolean.compare(
                                b.getContent().contains("发票"), a.getContent().contains("发票")))
                        .limit(topK)
                        .toList());

        List<DocumentChunk> result = retriever.retrieve("怎么开发票？", TOP_K);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getDocId()).isEqualTo("invoice");
    }

    private RetrievalEvaluator.EvalReport evaluate(List<RetrievalEvaluator.EvalCase> cases) {
        Retriever retriever = new HybridRetriever(
                new InMemoryVectorStore(new SimpleEmbeddingService()),
                RetrievalFixtures.DOCS);
        return new RetrievalEvaluator().evaluate(retriever, cases, TOP_K);
    }
}
