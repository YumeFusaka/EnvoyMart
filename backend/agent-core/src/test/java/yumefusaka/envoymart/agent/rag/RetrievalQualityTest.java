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

        // 实测 0.975 / 0.883（90 篇语料下不再是满分——同主题的多篇文档开始产生干扰）
        assertThat(report.hitRate()).isGreaterThanOrEqualTo(0.90);
        assertThat(report.mrr()).isGreaterThanOrEqualTo(0.80);
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

        // 顺带打一份 @5：面试里常见的对照是"别人的 Hit@5 是多少"，
        // 而 @3 与 @5 不同口径，没有同一份语料上的 @5 数字就没法直接比。
        RetrievalEvaluator.EvalReport at5 = evaluate(RetrievalFixtures.allCases(), 5);
        System.out.println("[检索评测-全量@5] " + at5);
        System.out.printf("[随机基线@5] corpus=%d topK=5 hitRate=%.3f%n",
                RetrievalFixtures.DOCS.size(),
                RetrievalFixtures.randomBaselineHitRate(RetrievalFixtures.DOCS.size(), 5));

        assertThat(report.caseCount()).isEqualTo(RetrievalFixtures.allCases().size());
        // 门槛按 90 篇语料 / 120 条样本的实测值（0.633 / 0.565 / 0.572）下留余量设定。
        // 关键词路无外部依赖、结果确定，余量留的是"分词或融合策略改动带来的正常波动"。
        assertThat(report.hitRate()).isGreaterThanOrEqualTo(0.58);
        assertThat(report.mrr()).isGreaterThanOrEqualTo(0.52);
        assertThat(report.ndcg()).isGreaterThanOrEqualTo(0.52);
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
        return evaluate(cases, TOP_K);
    }

    private RetrievalEvaluator.EvalReport evaluate(List<RetrievalEvaluator.EvalCase> cases, int topK) {
        Retriever retriever = new HybridRetriever(
                new InMemoryVectorStore(new SimpleEmbeddingService()),
                RetrievalFixtures.DOCS);
        return new RetrievalEvaluator().evaluate(retriever, cases, topK);
    }
}
