package yumefusaka.envoymart.aiservice.eval;

import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.GroundingEvaluator;
import yumefusaka.envoymart.agent.rag.QueryExpansions;
import yumefusaka.envoymart.agent.rag.RetrievalOutcome;

import java.util.List;
import java.util.Map;

/**
 * 评测快照的稳定持久化契约。
 *
 * <p>这里刻意不复用运行时对象：DocumentChunk、ToolExecution 等对象会随检索实现
 * 增加内部字段，直接让 Jackson 读写它们会把一次重构变成历史快照不可读。DTO 只保留
 * 页面需要核验的字符串、数字、布尔值和列表，并由显式映射负责向后兼容。</p>
 */
public final class EvalSnapshotDto {

    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final String RETRIEVAL_TYPE = "production-retrieval";
    public static final String GROUNDING_TYPE = "production-grounding";

    public enum Status {
        NEVER_RUN,
        RUNNING,
        COMPLETED,
        SNAPSHOT_CORRUPTED,
        VERSION_INCOMPATIBLE,
        FAILED
    }

    private EvalSnapshotDto() {
    }

    /** 文件外壳。payload 的具体类型由评测服务按 snapshotType 解码。 */
    public record Envelope(int schemaVersion, String snapshotType, String runId,
                           String writtenAt, Object payload) {
    }

    public record Evidence(String chunkId, String docId, String content, int chunkIndex,
                           String type, Long timestamp, String title, String source,
                           String scope, String version, String position, Integer charOffset,
                           Double score, Boolean reranked, Boolean graphBacked) {
        static Evidence from(DocumentChunk chunk) {
            if (chunk == null) {
                return null;
            }
            return new Evidence(chunk.getChunkId(), chunk.getDocId(), chunk.getContent(),
                    chunk.getChunkIndex(), chunk.getType(), chunk.getTimestamp(), chunk.getTitle(),
                    chunk.getSource(), chunk.getScope(), chunk.getVersion(), chunk.getPosition(),
                    chunk.getCharOffset(), chunk.getScore(), chunk.getReranked(), chunk.getGraphBacked());
        }

        DocumentChunk toDomain() {
            if (this == null) {
                return null;
            }
            return DocumentChunk.builder()
                    .chunkId(chunkId)
                    .docId(docId)
                    .content(content)
                    .chunkIndex(chunkIndex)
                    .type(type)
                    .timestamp(timestamp)
                    .title(title)
                    .source(source)
                    .scope(scope)
                    .version(version)
                    .position(position)
                    .charOffset(charOffset)
                    .score(score)
                    .reranked(reranked)
                    .graphBacked(graphBacked)
                    .build();
        }
    }

    public record Expansion(String hypothetical, List<String> angles) {
        static Expansion from(QueryExpansions expansion) {
            if (expansion == null) {
                return null;
            }
            return new Expansion(expansion.hypothetical(), safeList(expansion.angles()));
        }

        QueryExpansions toDomain() {
            return new QueryExpansions(hypothetical, safeList(angles));
        }
    }

    public record RetrievalTrace(String query, int vectorCandidates, int keywordCandidates,
                                 int graphCandidates, int fusedCandidates, int rerankedCandidates,
                                 String rerankQuery, boolean rerankApplied) {
        static RetrievalTrace from(RetrievalOutcome.RetrievalTrace trace) {
            if (trace == null) {
                return null;
            }
            return new RetrievalTrace(trace.query(), trace.vectorCandidates(), trace.keywordCandidates(),
                    trace.graphCandidates(), trace.fusedCandidates(), trace.rerankedCandidates(),
                    trace.rerankQuery(), trace.rerankApplied());
        }

        RetrievalOutcome.RetrievalTrace toDomain() {
            return new RetrievalOutcome.RetrievalTrace(query, vectorCandidates, keywordCandidates,
                    graphCandidates, fusedCandidates, rerankedCandidates, rerankQuery, rerankApplied);
        }
    }

    public record RetrievalMetrics(int topK, int caseCount, double hitRate, double mrr, double ndcg) {
        static RetrievalMetrics from(ProductionRetrievalEvalService.Metrics metrics) {
            return metrics == null ? null : new RetrievalMetrics(metrics.topK(), metrics.caseCount(),
                    metrics.hitRate(), metrics.mrr(), metrics.ndcg());
        }

        ProductionRetrievalEvalService.Metrics toDomain() {
            return new ProductionRetrievalEvalService.Metrics(topK, caseCount, hitRate, mrr, ndcg);
        }
    }

    public record RetrievalCorpus(int documents, int cases, int topK) {
        static RetrievalCorpus from(ProductionRetrievalEvalService.Corpus corpus) {
            return corpus == null ? null : new RetrievalCorpus(corpus.documents(), corpus.cases(), corpus.topK());
        }

        ProductionRetrievalEvalService.Corpus toDomain() {
            return new ProductionRetrievalEvalService.Corpus(documents, cases, topK);
        }
    }

    public record RetrievalStratum(String key, String label, String note, RetrievalMetrics metrics) {
        static RetrievalStratum from(ProductionRetrievalEvalService.StratumReport report) {
            return report == null ? null : new RetrievalStratum(report.key(), report.label(), report.note(),
                    RetrievalMetrics.from(report.metrics()));
        }

        ProductionRetrievalEvalService.StratumReport toDomain() {
            return new ProductionRetrievalEvalService.StratumReport(key, label, note,
                    metrics == null ? null : metrics.toDomain());
        }
    }

    public record RetrievalCase(String query, String stratum, List<String> relevantDocIds,
                                List<String> retrievedDocIds, boolean hit, int hitRank,
                                boolean graphRequired, boolean graphEvidenceHit,
                                Map<String, String> retrievedTitles, RetrievalTrace trace,
                                Expansion expansions, List<Evidence> evidence) {
        static RetrievalCase from(ProductionRetrievalEvalService.CaseReport report) {
            return report == null ? null : new RetrievalCase(report.query(), report.stratum(),
                    safeList(report.relevantDocIds()), safeList(report.retrievedDocIds()), report.hit(),
                    report.hitRank(), report.graphRequired(), report.graphEvidenceHit(),
                    report.retrievedTitles() == null ? Map.of() : Map.copyOf(report.retrievedTitles()),
                    RetrievalTrace.from(report.trace()), Expansion.from(report.expansions()),
                    EvalSnapshotDto.evidence(report.evidence()));
        }

        ProductionRetrievalEvalService.CaseReport toDomain() {
            return new ProductionRetrievalEvalService.CaseReport(query, stratum, safeList(relevantDocIds),
                    safeList(retrievedDocIds), hit, hitRank, graphRequired, graphEvidenceHit,
                    retrievedTitles == null ? Map.of() : Map.copyOf(retrievedTitles),
                    trace == null ? null : trace.toDomain(),
                    expansions == null ? null : expansions.toDomain(), toEvidence(evidence));
        }
    }

    public record GraphPathMetrics(int requiredCases, int hitCases, double hitRate) {
        static GraphPathMetrics from(ProductionRetrievalEvalService.GraphPathMetrics metrics) {
            return metrics == null ? null : new GraphPathMetrics(metrics.requiredCases(), metrics.hitCases(), metrics.hitRate());
        }

        ProductionRetrievalEvalService.GraphPathMetrics toDomain() {
            return new ProductionRetrievalEvalService.GraphPathMetrics(requiredCases, hitCases, hitRate);
        }
    }

    public record RetrievalSnapshot(String status, String trigger, String generatedAt, long durationMs,
                                    RetrievalCorpus corpus, String pipeline, RetrievalMetrics overall,
                                    List<RetrievalStratum> strata, List<RetrievalCase> cases,
                                    GraphPathMetrics graphPath, String error, String runId,
                                    String snapshotPath, String snapshotError) {
        public static RetrievalSnapshot from(ProductionRetrievalEvalService.Report report) {
            return new RetrievalSnapshot(report.status(), report.trigger(), report.generatedAt(), report.durationMs(),
                    RetrievalCorpus.from(report.corpus()), report.pipeline(), RetrievalMetrics.from(report.overall()),
                    report.strata() == null ? List.of() : report.strata().stream().map(RetrievalStratum::from).toList(),
                    report.cases() == null ? List.of() : report.cases().stream().map(RetrievalCase::from).toList(),
                    GraphPathMetrics.from(report.graphPath()), report.error(), report.runId(), report.snapshotPath(),
                    report.snapshotError());
        }

        public ProductionRetrievalEvalService.Report toDomain() {
            return new ProductionRetrievalEvalService.Report(compatStatus(status), trigger, generatedAt, durationMs,
                    corpus == null ? null : corpus.toDomain(), pipeline,
                    overall == null ? null : overall.toDomain(),
                    strata == null ? List.of() : strata.stream().map(RetrievalStratum::toDomain).toList(),
                    cases == null ? List.of() : cases.stream().map(RetrievalCase::toDomain).toList(),
                    graphPath == null ? null : graphPath.toDomain(), error, runId, snapshotPath, snapshotError);
        }
    }

    public record GroundingCitation(String sentence, List<String> anchors, List<Integer> refs, String verdict) {
        static GroundingCitation from(GroundingEvaluator.CitationCheck check) {
            return check == null ? null : new GroundingCitation(check.sentence(), safeList(check.anchors()),
                    check.refs() == null ? List.of() : List.copyOf(check.refs()),
                    check.verdict() == null ? null : check.verdict().name());
        }

        GroundingEvaluator.CitationCheck toDomain() {
            GroundingEvaluator.CitationVerdict parsed = verdict == null
                    ? GroundingEvaluator.CitationVerdict.MISSING : GroundingEvaluator.CitationVerdict.valueOf(verdict);
            return new GroundingEvaluator.CitationCheck(sentence, safeList(anchors),
                    refs == null ? List.of() : List.copyOf(refs), parsed);
        }
    }

    public record GroundingOutcome(String id, String kind, String mode, boolean answerable,
                                   int sentences, int factSentences, int unsupported, int checks,
                                   int wrongCitations, int outOfRange, boolean gateRefused,
                                   boolean refusalCorrect, boolean multiHopHit,
                                   List<GroundingCitation> citations, String rootCause) {
        static GroundingOutcome from(GroundingEvaluator.CaseOutcome outcome) {
            if (outcome == null) {
                return null;
            }
            return new GroundingOutcome(outcome.id(), outcome.kind() == null ? null : outcome.kind().name(),
                    outcome.mode() == null ? null : outcome.mode().name(), outcome.answerable(), outcome.sentences(),
                    outcome.factSentences(), outcome.unsupported(), outcome.checks(), outcome.wrongCitations(),
                    outcome.outOfRange(), outcome.gateRefused(), outcome.refusalCorrect(), outcome.multiHopHit(),
                    outcome.citations() == null ? List.of() : outcome.citations().stream().map(GroundingCitation::from).toList(),
                    outcome.rootCause());
        }

        GroundingEvaluator.CaseOutcome toDomain() {
            yumefusaka.envoymart.agent.rag.GroundingFixtures.Kind parsedKind;
            try {
                parsedKind = kind == null ? yumefusaka.envoymart.agent.rag.GroundingFixtures.Kind.ANSWERABLE
                        : yumefusaka.envoymart.agent.rag.GroundingFixtures.Kind.valueOf(kind);
            } catch (IllegalArgumentException ignored) {
                parsedKind = yumefusaka.envoymart.agent.rag.GroundingFixtures.Kind.ANSWERABLE;
            }
            GroundingEvaluator.Mode parsedMode;
            try {
                parsedMode = mode == null ? GroundingEvaluator.Mode.NOT_APPLICABLE : GroundingEvaluator.Mode.valueOf(mode);
            } catch (IllegalArgumentException ignored) {
                parsedMode = GroundingEvaluator.Mode.NOT_APPLICABLE;
            }
            return new GroundingEvaluator.CaseOutcome(id, parsedKind, parsedMode, answerable, sentences, factSentences, unsupported, checks,
                    wrongCitations, outOfRange, gateRefused, refusalCorrect, multiHopHit,
                    citations == null ? List.of() : citations.stream().map(GroundingCitation::toDomain).toList(), rootCause);
        }
    }

    public record GroundingMetrics(int caseCount, double hallucinationRate, int factSentences,
                                   int unsupportedSentences, double citationAccuracy, int citationChecks,
                                   int wrongCitations, int outOfRangeCitations, double refusalAccuracy,
                                   int missedRefusals, int overRefusals, double multiHopHitRate,
                                   int multiHopCases) {
        static GroundingMetrics from(GroundingEvaluator.Metrics metrics) {
            return metrics == null ? null : new GroundingMetrics(metrics.caseCount(), metrics.hallucinationRate(),
                    metrics.factSentences(), metrics.unsupportedSentences(), metrics.citationAccuracy(),
                    metrics.citationChecks(), metrics.wrongCitations(), metrics.outOfRangeCitations(),
                    metrics.refusalAccuracy(), metrics.missedRefusals(), metrics.overRefusals(),
                    metrics.multiHopHitRate(), metrics.multiHopCases());
        }

        GroundingEvaluator.Metrics toDomain() {
            return new GroundingEvaluator.Metrics(caseCount, hallucinationRate, factSentences, unsupportedSentences,
                    citationAccuracy, citationChecks, wrongCitations, outOfRangeCitations, refusalAccuracy,
                    missedRefusals, overRefusals, multiHopHitRate, multiHopCases);
        }
    }

    public record Tool(String tool, String input, String output, boolean success, boolean noData,
                       long latencyMs, List<Evidence> evidence, Map<String, String> facts,
                       List<String> entities) {
        static Tool from(ToolExecution execution) {
            return execution == null ? null : new Tool(execution.getTool(), execution.getInput(), execution.getOutput(),
                    execution.isSuccess(), execution.isNoData(), execution.getLatencyMs(), EvalSnapshotDto.evidence(execution.getEvidence()),
                    execution.getFacts() == null ? Map.of() : Map.copyOf(execution.getFacts()),
                    execution.getEntities() == null ? List.of() : List.copyOf(execution.getEntities()));
        }

        ToolExecution toDomain() {
            return ToolExecution.builder().tool(tool).input(input).output(output).success(success).noData(noData)
                    .latencyMs(latencyMs).evidence(toEvidence(evidence)).facts(facts == null ? Map.of() : Map.copyOf(facts))
                    .entities(entities == null ? List.of() : List.copyOf(entities)).build();
        }
    }

    public record GroundingCase(String question, GroundingOutcome outcome, String answer,
                                int evidenceCount, long latencyMs, String error, List<Evidence> evidence,
                                String retrievalQuery, Expansion expansion, List<Tool> toolExecutions,
                                RetrievalTrace retrievalTrace, String requestId) {
        static GroundingCase from(GroundingLiveEvalService.CaseResult result) {
                    return result == null ? null : new GroundingCase(result.question(), GroundingOutcome.from(result.outcome()),
                    result.answer(), result.evidenceCount(), result.latencyMs(), result.error(), EvalSnapshotDto.evidence(result.evidence()),
                    result.retrievalQuery(), Expansion.from(result.expansion()),
                    result.toolExecutions() == null ? List.of() : result.toolExecutions().stream().map(Tool::from).toList(),
                    RetrievalTrace.from(result.retrievalTrace()), result.requestId());
        }

        GroundingLiveEvalService.CaseResult toDomain() {
            return new GroundingLiveEvalService.CaseResult(question, outcome == null ? null : outcome.toDomain(), answer,
                    evidenceCount, latencyMs, error, toEvidence(evidence), retrievalQuery,
                    expansion == null ? null : expansion.toDomain(),
                    toolExecutions == null ? List.of() : toolExecutions.stream().map(Tool::toDomain).toList(),
                    retrievalTrace == null ? null : retrievalTrace.toDomain(), requestId);
        }
    }

    public record GroundingSnapshot(String status, String startedAt, String finishedAt, int totalCases,
                                    int completedCases, int failedCases, String currentQuestion, String userId,
                                    GroundingMetrics metrics, List<GroundingCase> cases, String promptVersion,
                                    String pipelineVersion, String runId, String snapshotPath,
                                    String snapshotError) {
        static GroundingSnapshot from(GroundingLiveEvalService.LiveRun run) {
            return new GroundingSnapshot(run.status(), run.startedAt(), run.finishedAt(), run.totalCases(),
                    run.completedCases(), run.failedCases(), run.currentQuestion(), run.userId(),
                    GroundingMetrics.from(run.metrics()), run.cases() == null ? List.of() : run.cases().stream()
                    .map(GroundingCase::from).toList(), run.promptVersion(), run.pipelineVersion(), run.runId(),
                    run.snapshotPath(), run.snapshotError());
        }

        GroundingLiveEvalService.LiveRun toDomain() {
            return new GroundingLiveEvalService.LiveRun(compatStatus(status), startedAt, finishedAt, totalCases, completedCases,
                    failedCases, currentQuestion, userId, metrics == null ? null : metrics.toDomain(),
                    cases == null ? List.of() : cases.stream().map(GroundingCase::toDomain).toList(), promptVersion,
                    pipelineVersion, runId, snapshotPath, snapshotError);
        }
    }

    static List<Evidence> evidence(List<DocumentChunk> chunks) {
        return chunks == null ? List.of() : chunks.stream().map(Evidence::from).toList();
    }

    static List<DocumentChunk> toEvidence(List<Evidence> chunks) {
        return chunks == null ? List.of() : chunks.stream().map(Evidence::toDomain).toList();
    }

    static <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    private static String compatStatus(String status) {
        return status == null || status.isBlank() || "NEVER".equals(status) || "IDLE".equals(status)
                ? Status.NEVER_RUN.name() : status;
    }
}
