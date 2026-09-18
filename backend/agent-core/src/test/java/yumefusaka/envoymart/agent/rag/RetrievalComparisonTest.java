package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索效果对照实验 —— 同一套样本跑三条链路，量化"引入向量与重排"的收益。
 * <p>
 * 与 {@link RetrievalQualityTest} 的分工：
 * <ul>
 *   <li>那个是 <b>CI 回归门禁</b>：无外部依赖、可重复、只卡关键词路的底线；</li>
 *   <li>这个是 <b>效果对照</b>：需要真实 embedding 与重排服务，<b>只在本地跑</b>，
 *       用于回答"加了向量到底提升多少"。</li>
 * </ul>
 * 需要显式开关 {@code RUN_RETRIEVAL_COMPARISON=true} 与 {@code DASHSCOPE_API_KEY}。
 * <p>
 * 之所以要显式开关而不是只看 Key 是否存在：这个测试会产生真实的 API 调用，
 * 不该被"碰巧配了 Key"的开发者在每次 {@code mvn test} 时静默触发。
 * <p>
 * 运行：
 * <pre>
 * RUN_RETRIEVAL_COMPARISON=true DASHSCOPE_API_KEY=xxx \
 *   mvn -pl agent-core test -Dtest=RetrievalComparisonTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RUN_RETRIEVAL_COMPARISON", matches = "true")
class RetrievalComparisonTest {

    private static final int TOP_K = 3;
    private static final String EMBEDDING_MODEL = "text-embedding-v4";
    private static final String RERANK_MODEL = "gte-rerank-v2";

    @Test
    void 对照三种检索配置的效果() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        // 配置一：仅关键词 —— 向量库留空，等价于线上未接向量库的降级态
        Retriever bm25Only = new HybridRetriever(
                new InMemoryVectorStore(new SimpleEmbeddingService()), RetrievalFixtures.DOCS);

        // 配置二与三共用真实向量库。
        // 先建待测检索器，再让引擎用同一条线上摄取路径（SimpleRAGEngine.ingestBatch）灌数据——
        // 检索器只在 retrieve() 时读向量库，构造顺序上不构成循环。
        //
        // 必须走真实切片：手工造切片时很容易把 chunkId 和 docId 设成同一个值，
        // 那样「RRF 按 docId 而非 chunkId 对齐」这个修复就完全测不出来——
        // 两种对齐方式会得到一模一样的结果。真实切片的 chunkId 是 docId_index，两者不同。
        InMemoryVectorStore vectorStore = new InMemoryVectorStore(
                new DashScopeEmbeddingService(apiKey, EMBEDDING_MODEL));
        // 超时放宽到 30 秒：线上默认 5 秒是给单次交互用的，评测要连续打 240 次，
        // 服务端在密集请求下响应变慢就会超时——而 HttpTimeoutException 的 getMessage()
        // 返回 null，降级原因看上去"没有原因"，排查时极易被误读成"服务端返回了坏数据"。
        DashScopeReranker reranker = new DashScopeReranker(
                apiKey, RERANK_MODEL, null, java.time.Duration.ofSeconds(30));
        Retriever hybrid = new HybridRetriever(vectorStore, RetrievalFixtures.DOCS);
        Retriever hybridWithRerank = new HybridRetriever(
                vectorStore, RetrievalFixtures.DOCS, reranker);
        new SimpleRAGEngine(vectorStore, hybrid,
                RetrievalFixtures.CHUNK_SIZE, RetrievalFixtures.CHUNK_OVERLAP)
                .ingestBatch(RetrievalFixtures.DOCS);

        System.out.println();
        System.out.println("========== 检索效果对照（语料 " + RetrievalFixtures.DOCS.size()
                + " 篇，样本 " + RetrievalFixtures.allCases().size() + " 条，topK=" + TOP_K + "）==========");
        System.out.printf("随机基线 Hit Rate@3 = %.3f%n%n",
                RetrievalFixtures.randomBaselineHitRate(RetrievalFixtures.DOCS.size(), TOP_K));

        report("仅关键词(BM25)", bm25Only);
        report("混合(BM25+向量)", hybrid);
        report("混合+重排", hybridWithRerank);
        System.out.println("======================================================================");

        // 重排降级统计。
        // 降级后的返回与"配置里根本没接重排"逐位相同，从指标上完全看不出区别——
        // 实测中同一份代码、同一套数据，语义档在 0.6 与 0.7 之间跳动，差异就来自这里。
        // 不打出来，任何"重排效果如何"的结论都无从判断可信度。
        int success = reranker.successCount();
        int degraded = reranker.degradedCount();
        System.out.printf("重排调用：生效 %d 次，降级 %d 次%n", success, degraded);
        if (degraded > 0) {
            System.out.println("⚠️ 存在降级（原因示例：" + reranker.lastDegradeReason() + "）");
            System.out.println("   降级等价于不重排 → 本轮的「混合+重排」数字不可复现，不代表重排的真实效果。");
        }

        // 夹具自检：三档必须等量，否则分档指标不可横向比较
        assertThat(RetrievalFixtures.LEXICAL_CASES).hasSameSizeAs(RetrievalFixtures.PARAPHRASE_CASES);
        assertThat(RetrievalFixtures.PARAPHRASE_CASES).hasSameSizeAs(RetrievalFixtures.HARD_CASES);
        assertThat(success)
                .as("全部降级意味着这一档实际是「混合」的副本，不能当作重排结果")
                .isGreaterThan(0);
    }

    private void report(String name, Retriever retriever) {
        RetrievalEvaluator evaluator = new RetrievalEvaluator();
        System.out.println("--- " + name + " ---");
        print("字面", evaluator.evaluate(retriever, RetrievalFixtures.LEXICAL_CASES, TOP_K));
        print("口语", evaluator.evaluate(retriever, RetrievalFixtures.PARAPHRASE_CASES, TOP_K));
        print("语义", evaluator.evaluate(retriever, RetrievalFixtures.HARD_CASES, TOP_K));
        print("全量", evaluator.evaluate(retriever, RetrievalFixtures.allCases(), TOP_K));
        System.out.println();
    }

    private void print(String group, RetrievalEvaluator.EvalReport report) {
        System.out.printf("  %s  cases=%d  HitRate@%d=%.3f  MRR=%.3f  NDCG=%.3f%n",
                group, report.caseCount(), report.topK(),
                report.hitRate(), report.mrr(), report.ndcg());
    }
}
