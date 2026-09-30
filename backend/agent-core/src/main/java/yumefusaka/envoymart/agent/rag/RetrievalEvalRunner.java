package yumefusaka.envoymart.agent.rag;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索评测运行器 —— 报告页「重新运行」按钮背后的那一次执行。
 * <p>
 * 与 CI 门禁（{@code RetrievalQualityTest}）读同一份夹具（{@link EvalFixtures}）、
 * 用同一套检索器构造（关键词路，向量库留空），因此页面上的数字与 CI 的数字
 * 出自同一条链路、逐位一致——「页面上好看、CI 里是另一回事」在这里结构性地不可能。
 * <p>
 * <b>它测的是关键词路的底线，不是线上全链路的水平</b>：接入真实向量与重排后的对照
 * 需要模型调用（非确定性、要 API key），只能作为历史记录展示，不能现场重跑。
 * 报告页把这两类证据分开呈现，运行器只负责能确定性复现的那一类。
 * <p>
 * 全程本地计算、无外部依赖，一次 <b>120 条 × 90 篇</b>的评测是毫秒级——
 * 所以启动时可以放心跑一次作为基线快照，不必持久化任何东西。
 */
public class RetrievalEvalRunner {

    /** 触发来源 —— 报告页据此说明「这个数字是什么时候、因为什么产生的」 */
    public static final String TRIGGER_STARTUP = "STARTUP";
    public static final String TRIGGER_MANUAL = "MANUAL";

    private static final int TOP_K = 3;

    public record Corpus(int documents, int cases, int chunkSize, int chunkOverlap) {
    }

    public record Metrics(int topK, int caseCount, double hitRate, double mrr, double ndcg) {
    }

    /** 随机排序基线 —— 实测值必须和它一起看，否则"0.633 是高是低"没有参照 */
    public record Baseline(double hitRateAt3, double hitRateAt5) {
    }

    public record StratumReport(String key, String label, Metrics metrics) {
    }

    /** 一条样本的现场结果。失败样本（hit=false）是报告页上信息量最大的行 */
    public record CaseReport(String query, String stratum, List<String> relevantDocIds,
                             List<String> retrievedDocIds, boolean hit, int hitRank) {
    }

    public record EvalRun(String generatedAt, String trigger, Corpus corpus,
                          Metrics overallAt3, Metrics overallAt5, Baseline baseline,
                          List<StratumReport> strata, List<CaseReport> cases) {
    }

    public EvalRun run(String trigger) {
        Retriever retriever = new HybridRetriever(
                new InMemoryVectorStore(new SimpleEmbeddingService()),
                EvalFixtures.DOCS);
        RetrievalEvaluator evaluator = new RetrievalEvaluator();

        // 全量逐条只检索一遍（topK=3），分层聚合从同一份逐条结果切分——
        // 每层单独再跑一遍的话，两层结果漂移时页面上的分档与整体分就对不上账
        Map<String, String> stratumOf = new LinkedHashMap<>();
        for (EvalFixtures.Stratum stratum : EvalFixtures.strata()) {
            for (RetrievalEvaluator.EvalCase evalCase : stratum.cases()) {
                stratumOf.put(evalCase.query(), stratum.key());
            }
        }
        List<RetrievalEvaluator.CaseOutcome> outcomes =
                evaluator.evaluateEach(retriever, EvalFixtures.allCases(), TOP_K);

        List<CaseReport> cases = new ArrayList<>(outcomes.size());
        for (RetrievalEvaluator.CaseOutcome outcome : outcomes) {
            cases.add(new CaseReport(outcome.query(), stratumOf.get(outcome.query()),
                    outcome.relevantDocIds(), outcome.retrievedDocIds(),
                    outcome.hit(), outcome.hitRank()));
        }

        List<StratumReport> strata = new ArrayList<>();
        for (EvalFixtures.Stratum stratum : EvalFixtures.strata()) {
            List<RetrievalEvaluator.CaseOutcome> slice = outcomes.stream()
                    .filter(outcome -> stratum.key().equals(stratumOf.get(outcome.query())))
                    .toList();
            strata.add(new StratumReport(stratum.key(), stratum.label(),
                    toMetrics(RetrievalEvaluator.summarize(slice, TOP_K))));
        }

        RetrievalEvaluator.EvalReport at5 = evaluator.evaluate(retriever, EvalFixtures.allCases(), 5);

        return new EvalRun(
                OffsetDateTime.now().toString(),
                trigger,
                new Corpus(EvalFixtures.DOCS.size(), EvalFixtures.allCases().size(),
                        EvalFixtures.CHUNK_SIZE, EvalFixtures.CHUNK_OVERLAP),
                toMetrics(RetrievalEvaluator.summarize(outcomes, TOP_K)),
                toMetrics(at5),
                new Baseline(
                        EvalFixtures.randomBaselineHitRate(EvalFixtures.DOCS.size(), 3),
                        EvalFixtures.randomBaselineHitRate(EvalFixtures.DOCS.size(), 5)),
                strata,
                cases);
    }

    private static Metrics toMetrics(RetrievalEvaluator.EvalReport report) {
        return new Metrics(report.topK(), report.caseCount(),
                report.hitRate(), report.mrr(), report.ndcg());
    }
}
