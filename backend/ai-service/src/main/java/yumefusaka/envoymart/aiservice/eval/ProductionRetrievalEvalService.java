package yumefusaka.envoymart.aiservice.eval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.rag.ProductionRetrievalFixtures;
import yumefusaka.envoymart.agent.rag.RetrievalEvaluator;
import yumefusaka.envoymart.agent.rag.Retriever;
import yumefusaka.envoymart.aiservice.knowledge.KnowledgeCorpus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 生产链路检索评测 —— 报告页「当前系统检索效果」那一栏的数据来源。
 * <p>
 * <b>它跑的是用户此刻真正在用的那条链路：</b>真实 embedding 的向量路 + BM25 词法路 +
 * 线上 Neo4j 的图谱路 → RRF 融合 → 百炼重排 → 取 top-K。查询还会经过与生产同一份
 * 查询扩写（HyDE 走语义路、角度改写走词法路）。语料就是线上那 47 篇文档，不是评测专用夹具。
 * <p>
 * <b>为什么必须落盘成快照，而不是每次访问现场跑。</b>这条链路里每一步都在调外部模型
 * （embedding、重排、可能还有扩写），一次全量评测是几十次到上百次计费调用、耗时以分钟计，
 * 且同一份输入两次运行的数字会因采样而略有出入。用户看的是一个「某时刻的真实成绩单」，
 * 不是「每次打开都重算一遍的实时读数」——后者既贵又不稳定，还会让「这个数字是什么」失去意义。
 * 因此：<b>由管理员触发一次真跑，结果写成 JSON 快照，报告页只读快照</b>，
 * 并在页面上标明「这是哪一刻、用哪条链路跑出来的」。
 * <p>
 * <b>为什么异步 + 单飞。</b>真跑要几分钟，同步 HTTP 会先把客户端等超时；并发跑多个评测
 * 除了互相抢模型配额没有别的作用。所以一个时刻只允许一个任务，重复触发返回当前状态，
 * 前端轮询进度——与回答质量真跑同一套取舍。
 * <p>
 * 报告页只读取本服务产出的生产链路快照，不混入旧的确定性评测基线。
 */
@Slf4j
@Service
public class ProductionRetrievalEvalService {

    private static final String SNAPSHOT_FILE = "production-retrieval.json";

    private static final int TOP_K = 3;

    public static final String TRIGGER_MANUAL = "MANUAL";

    /** 生产链路的文字描述 —— 报告页要照着它向读者交代「这一栏跑的是什么」，两处各写一遍会漂移 */
    public static final String PIPELINE =
            "真实 embedding 向量路 + BM25 词法路 + 线上 Neo4j 图谱路 → RRF → 百炼重排 → 取 top-3";

    private final Retriever retriever;
    private final KnowledgeCorpus corpus;
    private final ObjectMapper objectMapper;
    private final EvalSnapshotStore snapshotStore;

    /** 单飞闸 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 最近一次真跑的进度/结果（内存态）。落盘快照是它的完成态副本 */
    private volatile Report latest;

    @Autowired
    public ProductionRetrievalEvalService(@Qualifier("retriever") Retriever retriever,
                                          KnowledgeCorpus corpus,
                                          ObjectMapper objectMapper,
                                          EvalSnapshotStore snapshotStore) {
        this.retriever = retriever;
        this.corpus = corpus;
        this.objectMapper = objectMapper;
        this.snapshotStore = snapshotStore;
    }

    public ProductionRetrievalEvalService(@Qualifier("retriever") Retriever retriever,
                                          KnowledgeCorpus corpus,
                                          ObjectMapper objectMapper) {
        this(retriever, corpus, objectMapper, new EvalSnapshotStore(objectMapper, java.nio.file.Path.of("data", "eval")));
    }

    /**
     * 报告页读到的那份数据：优先取内存里正在跑/刚跑完的进度，其次读落盘快照，
     * 都没有时返回「尚未运行」占位——页面据此显示空态与「运行一次」按钮，
     * 而不是拿一份编出来的数字充数。
     */
    public Report current() {
        Report inMemory = latest;
        if (inMemory != null) {
            return inMemory;
        }
        EvalSnapshotStore.ReadResult<EvalSnapshotDto.RetrievalSnapshot> stored =
                snapshotStore.read(SNAPSHOT_FILE, EvalSnapshotDto.RETRIEVAL_TYPE,
                        EvalSnapshotDto.RetrievalSnapshot.class);
        if (stored.value() != null) {
            return stored.value().toDomain();
        }
        if (stored.status() == EvalSnapshotDto.Status.SNAPSHOT_CORRUPTED
                || stored.status() == EvalSnapshotDto.Status.VERSION_INCOMPATIBLE) {
            log.warn("[Eval] 生产检索快照读取失败 status={} path={} reason={}", stored.status(), stored.path(), stored.error());
            return Report.error(stored.status(), stored.path(), stored.runId(), stored.error(),
                    ProductionRetrievalFixtures.allCases().size(), corpus.documents().size());
        }
        return Report.never();
    }

    /**
     * 启动一次真跑（异步）。已在跑时直接返回当前状态——连点两次按钮的人想要的是
     * 「看着它跑完」，不是「排上两个任务」。
     */
    public Report start() {
        if (!running.compareAndSet(false, true)) {
            return current();
        }
        Report seed = Report.running(ProductionRetrievalFixtures.allCases().size(), corpus.documents().size(), UUID.randomUUID().toString());
        latest = seed;
        Thread worker = new Thread(this::execute, "production-retrieval-eval");
        worker.setDaemon(true);
        worker.start();
        return seed;
    }

    private void execute() {
        long startedAt = System.currentTimeMillis();
        RetrievalEvaluator evaluator = new RetrievalEvaluator();
        List<ProductionRetrievalFixtures.Case> cases = ProductionRetrievalFixtures.allCases();

        Map<String, ProductionRetrievalFixtures.Stratum> stratumOf = new LinkedHashMap<>();
        List<RetrievalEvaluator.EvalCase> evalCases = new ArrayList<>(cases.size());
        for (ProductionRetrievalFixtures.Case c : cases) {
            stratumOf.put(c.query(), c.stratum());
            evalCases.add(new RetrievalEvaluator.EvalCase(c.query(), c.relevantDocIds()));
        }

        try {
            // 逐条检索、边跑边发布进度：一次真跑几分钟，前端要能看见它没有卡死
            List<RetrievalEvaluator.CaseOutcome> outcomes = new ArrayList<>(evalCases.size());
            Map<String, Map<String, String>> titlesById = new LinkedHashMap<>();
            for (RetrievalEvaluator.EvalCase evalCase : evalCases) {
                RetrievalEvaluator.CaseOutcome outcome =
                        evaluator.evaluateEach(retriever, List.of(evalCase), TOP_K).getFirst();
                outcomes.add(outcome);
                titlesById.put(outcome.query(), corpusTitleLookup(outcome.retrievedDocIds()));
                latest = progress(stratumOf, outcomes, titlesById, cases.size(), corpus.documents().size());
            }

            Report report = finish(outcomes, stratumOf, titlesById, cases.size(),
                    System.currentTimeMillis() - startedAt, latest == null ? null : latest.runId());
            persist(report);
            latest = report;
            log.info("[Eval] 生产链路检索评测完成：cases={} hitRate@{}={} 耗时={}ms",
                    cases.size(), TOP_K, report.overall().hitRate(), report.durationMs());
        } catch (RuntimeException e) {
            log.error("[Eval] 生产链路检索评测中断", e);
            // 中断也要留下一份可读状态，而不是永远停在 RUNNING
            latest = Report.failed(e.getClass().getSimpleName() + ": " + e.getMessage(),
                    latest == null ? ProductionRetrievalFixtures.allCases().size() : latest.corpus().cases(),
                    corpus.documents().size(), latest == null ? null : latest.runId());
        } finally {
            running.set(false);
        }
    }

    private Report progress(Map<String, ProductionRetrievalFixtures.Stratum> stratumOf,
                            List<RetrievalEvaluator.CaseOutcome> soFar,
                            Map<String, Map<String, String>> titlesById,
                            int total, int documents) {
        return new Report(EvalSnapshotDto.Status.RUNNING.name(), TRIGGER_MANUAL, null, 0,
                new Corpus(documents, total, TOP_K), PIPELINE,
                toMetrics(RetrievalEvaluator.summarize(soFar, TOP_K)),
                strata(soFar, stratumOf), cases(soFar, stratumOf, titlesById), graphMetrics(soFar), null,
                latest == null ? null : latest.runId(), null, null);
    }

    private Report finish(List<RetrievalEvaluator.CaseOutcome> outcomes,
                          Map<String, ProductionRetrievalFixtures.Stratum> stratumOf,
                          Map<String, Map<String, String>> titlesById, int total, long durationMs, String runId) {
        return new Report(EvalSnapshotDto.Status.COMPLETED.name(), TRIGGER_MANUAL, OffsetDateTime.now().toString(), durationMs,
                new Corpus(corpus.documents().size(), total, TOP_K), PIPELINE,
                toMetrics(RetrievalEvaluator.summarize(outcomes, TOP_K)),
                strata(outcomes, stratumOf), cases(outcomes, stratumOf, titlesById), graphMetrics(outcomes), null,
                runId, null, null);
    }

    private static GraphPathMetrics graphMetrics(List<RetrievalEvaluator.CaseOutcome> outcomes) {
        List<ProductionRetrievalFixtures.Case> graphCases = ProductionRetrievalFixtures.allCases().stream()
                .filter(ProductionRetrievalFixtures.Case::graphRequired).toList();
        Map<String, Boolean> hitByQuery = outcomes.stream().collect(java.util.stream.Collectors.toMap(
                RetrievalEvaluator.CaseOutcome::query, RetrievalEvaluator.CaseOutcome::graphEvidenceHit,
                (first, ignored) -> first));
        int completed = (int) graphCases.stream().filter(c -> hitByQuery.containsKey(c.query())).count();
        int hits = (int) graphCases.stream().filter(c -> Boolean.TRUE.equals(hitByQuery.get(c.query()))).count();
        return new GraphPathMetrics(completed, hits, completed == 0 ? 0 : (double) hits / completed);
    }

    private List<StratumReport> strata(List<RetrievalEvaluator.CaseOutcome> outcomes,
                                       Map<String, ProductionRetrievalFixtures.Stratum> stratumOf) {
        List<StratumReport> strata = new ArrayList<>();
        for (ProductionRetrievalFixtures.Stratum stratum : ProductionRetrievalFixtures.Stratum.values()) {
            List<RetrievalEvaluator.CaseOutcome> slice = outcomes.stream()
                    .filter(o -> stratum == stratumOf.get(o.query()))
                    .toList();
            strata.add(new StratumReport(stratum.key(), stratum.label(), stratum.note(),
                    toMetrics(RetrievalEvaluator.summarize(slice, TOP_K))));
        }
        return strata;
    }

    private List<CaseReport> cases(List<RetrievalEvaluator.CaseOutcome> outcomes,
                                   Map<String, ProductionRetrievalFixtures.Stratum> stratumOf,
                                   Map<String, Map<String, String>> titlesById) {
        List<CaseReport> reports = new ArrayList<>(outcomes.size());
        for (RetrievalEvaluator.CaseOutcome o : outcomes) {
            ProductionRetrievalFixtures.Case fixture = ProductionRetrievalFixtures.allCases().stream()
                    .filter(candidate -> candidate.query().equals(o.query())).findFirst().orElseThrow();
            reports.add(new CaseReport(o.query(), stratumOf.get(o.query()).key(),
                    o.relevantDocIds(), o.retrievedDocIds(), o.hit(), o.hitRank(),
                    fixture.graphRequired(), o.graphEvidenceHit(),
                    titlesById.getOrDefault(o.query(), Map.of()), o.trace(), o.expansions(), o.evidence()));
        }
        return reports;
    }

    /** 把召回文档编号翻成标题，报告页逐条明细里给人看的是标题而不是 KB-0005 */
    private Map<String, String> corpusTitleLookup(List<String> docIds) {
        Map<String, String> titles = new LinkedHashMap<>();
        for (String docId : docIds) {
            corpus.documents().stream()
                    .filter(doc -> docId.equals(doc.getId()))
                    .findFirst()
                    .ifPresent(doc -> titles.put(docId, doc.getTitle()));
        }
        return titles;
    }

    private void persist(Report report) {
        try {
            snapshotStore.write(SNAPSHOT_FILE, EvalSnapshotDto.RETRIEVAL_TYPE, report.runId(),
                    EvalSnapshotDto.RetrievalSnapshot.from(report));
        } catch (Exception e) {
            // 落盘失败不影响本次返回——用户已经等了几分钟拿到数字，不该因为写文件失败而白等。
            // 但下次访问会读不到，所以留痕
            log.error("[Eval] 生产检索快照落盘失败，本次结果只在内存中返回：{}", e.getMessage());
        }
    }

    private static Metrics toMetrics(RetrievalEvaluator.EvalReport report) {
        return new Metrics(report.topK(), report.caseCount(),
                report.hitRate(), report.mrr(), report.ndcg());
    }

    public record Metrics(int topK, int caseCount, double hitRate, double mrr, double ndcg) {
    }

    public record Corpus(int documents, int cases, int topK) {
    }

    public record StratumReport(String key, String label, String note, Metrics metrics) {
    }

    public record CaseReport(String query, String stratum, List<String> relevantDocIds,
                             List<String> retrievedDocIds, boolean hit, int hitRank,
                             boolean graphRequired, boolean graphEvidenceHit,
                             Map<String, String> retrievedTitles,
                             yumefusaka.envoymart.agent.rag.RetrievalOutcome.RetrievalTrace trace,
                             yumefusaka.envoymart.agent.rag.QueryExpansions expansions,
                             List<yumefusaka.envoymart.agent.rag.DocumentChunk> evidence) {
    }

    public record GraphPathMetrics(int requiredCases, int hitCases, double hitRate) {
    }

    /**
     * 报告页读到的一份数据。
     *
     * @param status      NEVER（从未跑过）/ RUNNING / COMPLETED / FAILED
     * @param pipeline    本次跑的是哪条链路，页面照它向读者交代，不让人猜
     * @param durationMs  真跑耗时，读者据此判断这份数字的获取成本
     * @param error       失败原因（只有 FAILED 时非空）
     */
    public record Report(String status, String trigger, String generatedAt, long durationMs,
                         Corpus corpus, String pipeline, Metrics overall,
                         List<StratumReport> strata, List<CaseReport> cases,
                         GraphPathMetrics graphPath, String error, String runId,
                         String snapshotPath, String snapshotError) {

        public boolean available() {
            return "COMPLETED".equals(status);
        }

        static Report never() {
            return new Report(EvalSnapshotDto.Status.NEVER_RUN.name(), null, null, 0,
                    new Corpus(0, ProductionRetrievalFixtures.allCases().size(), TOP_K), PIPELINE,
                    new Metrics(TOP_K, 0, 0, 0, 0), List.of(), List.of(), new GraphPathMetrics(0, 0, 0), null,
                    null, null, null);
        }

        static Report running(int totalCases, int documents, String runId) {
            return new Report(EvalSnapshotDto.Status.RUNNING.name(), TRIGGER_MANUAL, null, 0,
                    new Corpus(documents, totalCases, TOP_K), PIPELINE,
                    new Metrics(TOP_K, 0, 0, 0, 0), List.of(), List.of(), new GraphPathMetrics(0, 0, 0), null,
                    runId, null, null);
        }

        static Report failed(String error, int totalCases, int documents, String runId) {
            return new Report(EvalSnapshotDto.Status.FAILED.name(), TRIGGER_MANUAL, OffsetDateTime.now().toString(), 0,
                    new Corpus(documents, totalCases, TOP_K), PIPELINE,
                    new Metrics(TOP_K, 0, 0, 0, 0), List.of(), List.of(), new GraphPathMetrics(0, 0, 0), error,
                    runId, null, null);
        }

        static Report error(EvalSnapshotDto.Status status, java.nio.file.Path path, String runId,
                            String error, int totalCases, int documents) {
            return new Report(status.name(), null, null, 0,
                    new Corpus(documents, totalCases, TOP_K), PIPELINE,
                    new Metrics(TOP_K, 0, 0, 0, 0), List.of(), List.of(), new GraphPathMetrics(0, 0, 0),
                    error, runId, path == null ? null : path.toString(), error);
        }
    }
}
