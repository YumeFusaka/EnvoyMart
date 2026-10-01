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

    /**
     * 取几篇进 prompt。默认 3，与线上 {@code Agent.Config.ragTopK(3)} 保持一致——
     * 评测的 K 必须等于线上真正用的 K，否则测的是一个不存在的配置。
     * <p>
     * 可用 {@code RETRIEVAL_TOP_K=5} 跑一份 @5：用来回答"提高 topK 能换来多少召回"。
     * 注意 @3 与 @5 不是同一把尺子，**读数必须连随机基线一起看**——K 一大，
     * 基线本身也在涨。
     */
    private static final int TOP_K = Integer.parseInt(System.getenv().getOrDefault("RETRIEVAL_TOP_K", "3"));
    private static final String EMBEDDING_MODEL = "text-embedding-v4";
    private static final String RERANK_MODEL = "gte-rerank-v2";

    @Test
    void 对照三种检索配置的效果() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        // 配置一：仅关键词 —— 向量库留空，等价于线上未接向量库的降级态
        Retriever bm25Only = new HybridRetriever(
                new InMemoryVectorStore(new SimpleEmbeddingService()), EvalFixtures.DOCS);

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
        Retriever hybrid = new HybridRetriever(vectorStore, EvalFixtures.DOCS);
        HybridRetriever hybridWithRerank = new HybridRetriever(
                vectorStore, EvalFixtures.DOCS, reranker);
        new SimpleRAGEngine(vectorStore, hybrid,
                EvalFixtures.CHUNK_SIZE, EvalFixtures.CHUNK_OVERLAP)
                .ingestBatch(EvalFixtures.DOCS);

        // 扩写配置用**预录夹具**而非现场调模型：现场调的话，120 条查询要 3.5 分钟，
        // 而且每次都得到不同的扩写——那样这一行数字就不可复现，"提升了多少"也就无从对照。
        // 预录夹具是真实模型输出，只是冻结在某一时刻（采集时间与模型打在下方的表头里）。
        RecordedQueryExpander expander = new RecordedQueryExpander();
        String capturedAt = RecordedQueryExpander.CAPTURED_AT;
        String expandModel = RecordedQueryExpander.MODEL;

        System.out.println();
        System.out.println("========== 检索效果对照（语料 " + EvalFixtures.DOCS.size()
                + " 篇，样本 " + EvalFixtures.allCases().size() + " 条，topK=" + TOP_K + "）==========");
        System.out.printf("随机基线 Hit Rate@%d = %.3f%n%n", TOP_K,
                EvalFixtures.randomBaselineHitRate(EvalFixtures.DOCS.size(), TOP_K));

        report("仅关键词(BM25)", bm25Only);
        report("混合(BM25+向量)", hybrid);
        report("混合+重排", hybridWithRerank);
        // 归因配置：只喂角度、不喂假想答案。生产行为是两者都喂，
        // 但"提升了多少"与"是哪一半提升的"是两个问题，后者当场答不出来最容易被追问到崩
        report("混合+重排+角度改写", new MultiQueryRetriever(hybridWithRerank,
                new AnglesOnlyExpander(expander)));
        report("混合+重排+扩写(生产配置)", new MultiQueryRetriever(hybridWithRerank, expander));
        System.out.printf("扩写夹具：%d 条查询，采集于 %s（模型 %s）%n",
                RecordedQueryExpander.recordedCount(), capturedAt, expandModel);
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
        assertThat(EvalFixtures.LEXICAL_CASES).hasSameSizeAs(EvalFixtures.PARAPHRASE_CASES);
        assertThat(EvalFixtures.PARAPHRASE_CASES).hasSameSizeAs(EvalFixtures.HARD_CASES);
        assertThat(success)
                .as("全部降级意味着这一档实际是「混合」的副本，不能当作重排结果")
                .isGreaterThan(0);
    }

    private void report(String name, Retriever retriever) {
        RetrievalEvaluator evaluator = new RetrievalEvaluator();
        System.out.println("--- " + name + " ---");
        print("字面", evaluator.evaluate(retriever, EvalFixtures.LEXICAL_CASES, TOP_K));
        print("口语", evaluator.evaluate(retriever, EvalFixtures.PARAPHRASE_CASES, TOP_K));
        print("语义", evaluator.evaluate(retriever, EvalFixtures.HARD_CASES, TOP_K));
        print("全量", evaluator.evaluate(retriever, EvalFixtures.allCases(), TOP_K));
        System.out.println();
    }

    private void print(String group, RetrievalEvaluator.EvalReport report) {
        System.out.printf("  %s  cases=%d  HitRate@%d=%.3f  MRR=%.3f  NDCG=%.3f%n",
                group, report.caseCount(), report.topK(),
                report.hitRate(), report.mrr(), report.ndcg());
    }

    /** 归因用：把预录扩写里的假想答案丢掉，只留角度改写 */
    private record AnglesOnlyExpander(QueryExpander delegate) implements QueryExpander {
        @Override
        public QueryExpansions expand(String query) {
            return new QueryExpansions(null, delegate.expand(query).angles());
        }
    }
}
