package yumefusaka.envoymart.aiservice.eval;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.core.Agent;
import yumefusaka.envoymart.agent.core.AgentGraph;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.EvidenceGate;
import yumefusaka.envoymart.agent.rag.GroundingEvaluator;
import yumefusaka.envoymart.agent.rag.GroundingFixtures;
import yumefusaka.envoymart.agent.rag.GraphMultiHopFixtures;
import yumefusaka.envoymart.common.web.RequestId;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 回答质量的<b>线上真跑</b>评测 —— 拿夹具里的问题重新问一遍真实 Agent。
 * <p>
 * 这里跑的是当前模型、当前知识库和当前图谱，完成后落盘为只读快照。
 * <p>
 * <b>为什么是异步 + 单飞。</b>当前 36 条用例串行调模型，同步请求会超时，而并发跑多个评测
 * 除了互相抢配额没有别的作用。所以：一个时刻只允许一个任务，重复触发直接返回当前状态，
 * 前端轮询进度。
 * <p>
 * <b>每条用例一个全新的 userId + sessionId。</b>不是洁癖：长期记忆是按 userId 存的，
 * 同一个人连着问 36 个问题，后面几轮的 prompt 里会掺进前面几轮的画像与情节——
 * 那样测出来的就不是"这批问题答得怎么样"，而是"一个越问越熟的会话答得怎么样"。
 */
@Slf4j
@Service
public class GroundingLiveEvalService {

    private static final String SNAPSHOT_FILE = "production-grounding.json";
    private static final int LIVE_CASE_COUNT = GroundingFixtures.CASES.size() + GraphMultiHopFixtures.CASES.size();
    private static final String PROMPT_VERSION = "grounding-prompt-v4";
    private static final String PIPELINE_VERSION = "agent-live-v3-evidence-gate-boundary";

    /**
     * 一条用例的真跑结果。
     *
     * @param question 问题原文。判定结果里没有它（判定层不需要），但逐条明细要显示——
     *                 只给一个 G-01，读者没法核对这一条在问什么
     * @param outcome  对真实回答做确定性判定后的结果
     * @param answer   这一轮的真实回答，报告页要能逐字读——指标是摘要，答案才是证据
     * @param error    单条失败的原因。一条失败不该让整轮作废，但也不能悄悄消失：
     *                 它会把这一条从分母里摘掉，不写出来就成了"少算了一条"
     */
    public record CaseResult(String question, GroundingEvaluator.CaseOutcome outcome, String answer,
                             int evidenceCount, long latencyMs, String error, List<DocumentChunk> evidence,
                             String retrievalQuery,
                             yumefusaka.envoymart.agent.rag.QueryExpansions expansion,
                             List<yumefusaka.envoymart.agent.llm.ToolExecution> toolExecutions,
                             yumefusaka.envoymart.agent.rag.RetrievalOutcome.RetrievalTrace retrievalTrace,
                             String requestId) {
        public CaseResult(String question, GroundingEvaluator.CaseOutcome outcome, String answer,
                          int evidenceCount, long latencyMs, String error) {
            this(question, outcome, answer, evidenceCount, latencyMs, error, List.of(), null, null, List.of(), null, null);
        }
    }

    /**
     * 一次真跑的整体状态。<b>可序列化进响应体</b>，字段名即前端读到的名字。
     *
     * @param status          IDLE / RUNNING / COMPLETED
     * @param completedCases  已完成（含失败）的条数，前端据此画进度
     * @param metrics         只有全部跑完才有值：中途的指标是"越跑越像"的假数字
     */
    public record LiveRun(String status, String startedAt, String finishedAt, int totalCases,
                          int completedCases, int failedCases, String currentQuestion, String userId,
                          GroundingEvaluator.Metrics metrics, List<CaseResult> cases,
                          String promptVersion, String pipelineVersion, String runId,
                          String snapshotPath, String snapshotError) {

        static LiveRun idle(int totalCases) {
            return new LiveRun(EvalSnapshotDto.Status.NEVER_RUN.name(), null, null, totalCases, 0, 0,
                    null, null, null, List.of(), PROMPT_VERSION, PIPELINE_VERSION, null, null, null);
        }

        static LiveRun error(EvalSnapshotDto.Status status, java.nio.file.Path path,
                             String runId, String error) {
            return new LiveRun(status.name(), null, null, LIVE_CASE_COUNT, 0, 0, null, null, null,
                    List.of(), PROMPT_VERSION, PIPELINE_VERSION, runId,
                    path == null ? null : path.toString(), error);
        }
    }

    private final Agent agent;
    private final ObjectMapper objectMapper;
    private final EvalSnapshotStore snapshotStore;

    /** 单飞闸：评测要花 token，并发触发只会有多个任务抢同一份配额 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 最近一次结果。volatile 只赋值不原地改，与检索评测的快照同一套发布方式 */
    private volatile LiveRun latest;

    @Autowired
    public GroundingLiveEvalService(Agent agent, ObjectMapper objectMapper, EvalSnapshotStore snapshotStore) {
        this.agent = agent;
        this.objectMapper = objectMapper;
        this.snapshotStore = snapshotStore;
    }

    public GroundingLiveEvalService(Agent agent, ObjectMapper objectMapper) {
        this(agent, objectMapper, new EvalSnapshotStore(objectMapper, java.nio.file.Path.of("data", "eval")));
    }

    public LiveRun current() {
        LiveRun current = latest;
        if (current != null) {
            return current;
        }
        EvalSnapshotStore.ReadResult<EvalSnapshotDto.GroundingSnapshot> stored =
                snapshotStore.read(SNAPSHOT_FILE, EvalSnapshotDto.GROUNDING_TYPE,
                        EvalSnapshotDto.GroundingSnapshot.class);
        if (stored.value() != null) {
            LiveRun migrated = withVersion(stored.value().toDomain());
            latest = migrated;
            return migrated;
        }
        if (stored.status() == EvalSnapshotDto.Status.SNAPSHOT_CORRUPTED
                || stored.status() == EvalSnapshotDto.Status.VERSION_INCOMPATIBLE) {
            log.warn("[AI] 回答质量真实快照读取失败 status={} path={} reason={}", stored.status(), stored.path(), stored.error());
            return LiveRun.error(stored.status(), stored.path(), stored.runId(), stored.error());
        }
        return LiveRun.idle(LIVE_CASE_COUNT);
    }

    /**
     * 启动一次真跑。已在跑时直接返回当前状态（不排队、不报错）——
     * 连点两次按钮的用户想要的是"看着它跑完"，不是"排上两个任务"。
     */
    public LiveRun start() {
        if (!running.compareAndSet(false, true)) {
            return current();
        }
        LiveRun seed = new LiveRun(EvalSnapshotDto.Status.RUNNING.name(), OffsetDateTime.now().toString(), null,
                LIVE_CASE_COUNT, 0, 0, null, null, null, List.of(),
                PROMPT_VERSION, PIPELINE_VERSION, UUID.randomUUID().toString(), null, null);
        latest = seed;
        Thread worker = new Thread(this::execute, "grounding-live-eval");
        worker.setDaemon(true);
        worker.start();
        return seed;
    }

    private void execute() {
        // 每次运行一个独立的用户：上一轮的长期记忆不该影响这一轮
        String userId = "eval-" + System.currentTimeMillis();
        List<CaseResult> results = new ArrayList<>();
        int failed = 0;
        try {
            List<LiveCase> cases = new ArrayList<>();
            GroundingFixtures.CASES.forEach(fixtureCase -> cases.add(new LiveCase(
                    fixtureCase.id(), fixtureCase.question(), fixtureCase.kind(),
                    fixtureCase.expectRefuse(), fixtureCase.mustMention())));
            GraphMultiHopFixtures.CASES.forEach(fixtureCase -> cases.add(new LiveCase(
                    fixtureCase.id(), fixtureCase.question(), GroundingFixtures.Kind.MULTI_HOP,
                    fixtureCase.expectRefuse(), fixtureCase.mustMention())));
            for (LiveCase fixtureCase : cases) {
                publishRunning(userId, results, fixtureCase);
                CaseResult result = runCase(userId, fixtureCase);
                if (result.error() != null) {
                    failed++;
                }
                results.add(result);
            }
            // 判定在每条用例里已经算完了，这里只做汇总——失败的条目从分母里摘掉，
            // 它们已经被 failedCases 单独计数并在响应体里下发，不会凭空消失
            List<GroundingEvaluator.CaseOutcome> outcomes = results.stream()
                    .map(CaseResult::outcome)
                    .filter(java.util.Objects::nonNull)
                    .sorted(GroundingEvaluator.byId())
                    .toList();
            GroundingEvaluator.Metrics metrics = GroundingEvaluator.aggregate(outcomes);
            latest = new LiveRun(outcomes.isEmpty() ? EvalSnapshotDto.Status.FAILED.name() : EvalSnapshotDto.Status.COMPLETED.name(), latest.startedAt(), OffsetDateTime.now().toString(),
                    LIVE_CASE_COUNT, results.size(), failed, null, userId, outcomes.isEmpty() ? null : metrics,
                    List.copyOf(results), PROMPT_VERSION, PIPELINE_VERSION, latest.runId(), null, null);
            persist(latest);
            log.info("[AI] 回答质量真跑完成：cases={} 失败={} 幻觉率={} 引用准确率={} 拒答准确率={} 多跳命中率={}",
                    results.size(), failed, metrics.hallucinationRate(), metrics.citationAccuracy(),
                    metrics.refusalAccuracy(), metrics.multiHopHitRate());
        } catch (RuntimeException e) {
            // 整轮崩掉（例如 Agent 初始化失败）也要留下一份可读的状态，而不是永远停在 RUNNING
            log.error("[AI] 回答质量真跑中断", e);
            latest = new LiveRun(EvalSnapshotDto.Status.FAILED.name(), latest.startedAt(), OffsetDateTime.now().toString(),
                    LIVE_CASE_COUNT, results.size(), failed, null, userId, null,
                    List.copyOf(results), PROMPT_VERSION, PIPELINE_VERSION, latest.runId(), null, null);
        } finally {
            running.set(false);
        }
    }

    private void persist(LiveRun report) {
        try {
            snapshotStore.write(SNAPSHOT_FILE, EvalSnapshotDto.GROUNDING_TYPE, report.runId(),
                    EvalSnapshotDto.GroundingSnapshot.from(report));
        } catch (Exception e) {
            log.error("[AI] 回答质量真实快照落盘失败：{}", e.getMessage());
        }
    }

    private void publishRunning(String userId, List<CaseResult> done, LiveCase fixtureCase) {
        latest = new LiveRun(EvalSnapshotDto.Status.RUNNING.name(), latest.startedAt(), null, LIVE_CASE_COUNT,
                done.size(), 0, fixtureCase.question(), userId, null, List.copyOf(done),
                PROMPT_VERSION, PIPELINE_VERSION, latest.runId(), null, null);
    }

    private static LiveRun withVersion(LiveRun run) {
        if (run.cases() != null && !run.cases().isEmpty() && run.cases().stream()
                .allMatch(result -> AgentGraph.GENERATION_FAILED_REPLY.equals(result.answer()))) {
            return new LiveRun(EvalSnapshotDto.Status.FAILED.name(), run.startedAt(), run.finishedAt(), run.totalCases(),
                    run.completedCases(), run.cases().size(), null, run.userId(), null,
                    run.cases(), run.promptVersion(), run.pipelineVersion(), run.runId(), run.snapshotPath(), run.snapshotError());
        }
        List<CaseResult> migratedCases = run.cases() == null ? List.of() : run.cases().stream()
                .map(GroundingLiveEvalService::withRootCause)
                .toList();
        return new LiveRun(run.status(), run.startedAt(), run.finishedAt(), run.totalCases(),
                run.completedCases(), run.failedCases(), run.currentQuestion(), run.userId(),
                run.metrics(), migratedCases,
                run.promptVersion() == null ? "历史快照未记录" : run.promptVersion(),
                run.pipelineVersion() == null ? "历史快照未记录" : run.pipelineVersion(), run.runId(),
                run.snapshotPath(), run.snapshotError());
    }

    private static CaseResult withRootCause(CaseResult result) {
        GroundingEvaluator.CaseOutcome outcome = result.outcome();
        if (outcome == null || outcome.rootCause() != null && !outcome.rootCause().isBlank()) {
            return result;
        }
        String rootCause;
        if (outcome.outOfRange() > 0 || outcome.wrongCitations() > 0) {
            rootCause = "WRONG_CITATION";
        } else if (outcome.kind() == GroundingFixtures.Kind.MULTI_HOP && outcome.unsupported() > 0) {
            rootCause = "GRAPH_PATH_OR_GROUNDING";
        } else if (outcome.unsupported() > 0) {
            rootCause = "UNSUPPORTED_CLAIM";
        } else if (outcome.answerable() && outcome.gateRefused()) {
            rootCause = "OVER_REFUSAL";
        } else if (!outcome.answerable() && !outcome.gateRefused()) {
            rootCause = "MISSED_REFUSAL";
        } else {
            rootCause = "NONE";
        }
        GroundingEvaluator.CaseOutcome migrated = new GroundingEvaluator.CaseOutcome(
                outcome.id(), outcome.kind(), outcome.mode(), outcome.answerable(), outcome.sentences(),
                outcome.factSentences(), outcome.unsupported(), outcome.checks(), outcome.wrongCitations(),
                outcome.outOfRange(), outcome.gateRefused(), outcome.refusalCorrect(), outcome.multiHopHit(),
                outcome.citations(), rootCause);
        return new CaseResult(result.question(), migrated, result.answer(), result.evidenceCount(),
                result.latencyMs(), result.error(), result.evidence(), result.retrievalQuery(), result.expansion(),
                result.toolExecutions(), result.retrievalTrace(), result.requestId());
    }

    /**
     * 跑一条用例。
     * <p>
     * <b>异常在这里被兜住而不是往上抛</b>：模型超时、限流、知识库抖一下，都是单条的事，
     * 让它们把整轮评测带走，等于"网络抖一次就得重新点一遍按钮"。
     */
    private CaseResult runCase(String userId, LiveCase fixtureCase) {
        // 每条用例一个会话：多轮上下文在这里是噪声，不是能力
        String sessionId = "grounding-eval-" + fixtureCase.id() + "-" + System.currentTimeMillis();
        long started = System.currentTimeMillis();
        String requestId = RequestId.generate();
        String previousRequestId = MDC.get(RequestId.MDC_KEY);
        MDC.put(RequestId.MDC_KEY, requestId);
        try {
            // 无确认令牌：评测问的都是知识与检索类问题，不碰高危操作
            Agent.AgentResponse response = agent.chat(userId, sessionId, fixtureCase.question(), null);
            if ("fallback".equals(response.getSource())) {
                return new CaseResult(fixtureCase.question(), null, response.getReply(), 0,
                        System.currentTimeMillis() - started, "模型生成失败，已返回降级回复；此条不参与质量指标",
                        List.of(), effectiveRetrievalQuery(response.getRetrievalQuery(), response.getRetrieval()), response.getExpansion(), response.getToolExecutions(),
                        response.getRetrieval() == null ? null : response.getRetrieval().trace(), requestId);
            }
            long latency = System.currentTimeMillis() - started;
            List<DocumentChunk> evidence =
                    response.getKnowledge() == null ? List.of() : response.getKnowledge();
            boolean toolEvidence = response.getToolExecutions() != null
                    && !response.getToolExecutions().isEmpty();
            EvidenceGate.Level level = response.getEvidenceLevel() == null
                    ? EvidenceGate.Level.NONE : response.getEvidenceLevel();
            // 入口门可能先判 WEAK/NONE，但 Agent 随后通过 knowledge_search 或
            // interaction_check 补回了真实依据。评测最终回答时应使用最终证据状态，
            // 否则“成功补检索后答对”会被错误计为过拒。
            boolean knowledgeToolEvidence = response.getToolExecutions() != null
                    && response.getToolExecutions().stream()
                    .anyMatch(execution -> execution.isSuccess() && !execution.isNoData()
                            && ("knowledge_search".equals(execution.getTool())
                            || "interaction_check".equals(execution.getTool())));
            level = finalEvidenceLevel(level, knowledgeToolEvidence);

            GroundingEvaluator.Sample sample = new GroundingEvaluator.Sample(fixtureCase.id(),
                    fixtureCase.kind(), fixtureCase.expectRefuse(), fixtureCase.mustMention(),
                    evidence, response.getReply(), level, toolEvidence,
                    response.getSemanticExemptions(),
                    response.getRetrieval() != null && response.getRetrieval().hasGraphEvidence());
            GroundingEvaluator.CaseOutcome outcome =
                    GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
            return new CaseResult(fixtureCase.question(), outcome, response.getReply(),
                    evidence.size(), latency, null, evidence.stream()
                    .map(chunk -> chunk.toBuilder().embedding(null).indexText(null).build()).toList(),
                    effectiveRetrievalQuery(response.getRetrievalQuery(), response.getRetrieval()), response.getExpansion(), response.getToolExecutions(),
                    response.getRetrieval() == null ? null : response.getRetrieval().trace(), requestId);
        } catch (RuntimeException e) {
            log.warn("[AI] 回答质量真跑单条失败 id={} 问题={}", fixtureCase.id(), fixtureCase.question(), e);
            return new CaseResult(fixtureCase.question(), null, null, 0,
                    System.currentTimeMillis() - started,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), List.of(), null, null, List.of(), null, requestId);
        } finally {
            if (previousRequestId == null) {
                MDC.remove(RequestId.MDC_KEY);
            } else {
                MDC.put(RequestId.MDC_KEY, previousRequestId);
            }
        }
    }

    private static String effectiveRetrievalQuery(String rewrittenQuery,
                                                  yumefusaka.envoymart.agent.rag.RetrievalOutcome retrieval) {
        if (retrieval != null && retrieval.trace() != null
                && retrieval.trace().query() != null && !retrieval.trace().query().isBlank()) {
            return retrieval.trace().query();
        }
        if (rewrittenQuery != null && !rewrittenQuery.isBlank()) {
            return rewrittenQuery;
        }
        return null;
    }

    private record LiveCase(String id, String question, GroundingFixtures.Kind kind,
                            boolean expectRefuse, List<String> mustMention) {
    }

    static EvidenceGate.Level finalEvidenceLevel(EvidenceGate.Level initial,
                                                 boolean knowledgeToolEvidence) {
        return initial == EvidenceGate.Level.NONE && knowledgeToolEvidence
                ? EvidenceGate.Level.SUFFICIENT : initial;
    }
}
