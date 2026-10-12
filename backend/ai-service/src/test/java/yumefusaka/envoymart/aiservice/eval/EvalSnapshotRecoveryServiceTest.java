package yumefusaka.envoymart.aiservice.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.aiservice.knowledge.KnowledgeCorpus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvalSnapshotRecoveryServiceTest {
    @TempDir
    Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private EvalSnapshotDto.GroundingSnapshot groundingSnapshot(String runId, String finishedAt) {
        EvalSnapshotDto.GroundingMetrics metrics = new EvalSnapshotDto.GroundingMetrics(
                1, 0, 1, 0, 1, 1, 0, 0, 1, 0, 0, 1, 1);
        EvalSnapshotDto.GroundingOutcome outcome = new EvalSnapshotDto.GroundingOutcome(
                "H-01", "ANSWERABLE", "PER_SENTENCE", true, 1, 1, 0, 1,
                0, 0, false, true, false, List.of(), "NONE");
        EvalSnapshotDto.GroundingCase item = new EvalSnapshotDto.GroundingCase(
                "历史问题", outcome, "历史回答", 0, 1, null, List.of(), null, null,
                List.of(), null, "request-history");
        return new EvalSnapshotDto.GroundingSnapshot("COMPLETED", "2026-10-10T09:59:00+08:00",
                finishedAt, 1, 1, 0, null, "eval-history", metrics, List.of(item),
                "历史提示词", "历史链路", runId, null, null);
    }

    private EvalSnapshotDto.RetrievalSnapshot retrievalSnapshot(String runId, String generatedAt) {
        EvalSnapshotDto.RetrievalCorpus corpus = new EvalSnapshotDto.RetrievalCorpus(47, 1, 3);
        EvalSnapshotDto.RetrievalMetrics metrics = new EvalSnapshotDto.RetrievalMetrics(3, 1, 1, 1, 1);
        EvalSnapshotDto.RetrievalCase item = new EvalSnapshotDto.RetrievalCase(
                "历史检索", "LEXICAL", List.of("doc-1"), List.of("doc-1"), true, 1,
                false, false, java.util.Map.of("doc-1", "历史文档"), null, null, List.of());
        return new EvalSnapshotDto.RetrievalSnapshot("COMPLETED", "MANUAL", generatedAt, 10,
                corpus, "历史检索链路", metrics, List.of(), List.of(item),
                new EvalSnapshotDto.GraphPathMetrics(0, 0, 0), null, runId, null, null);
    }

    @Test
    void 回答报告保持当前错误并独立返回旧快照生成时间和身份() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory);
        var old = store.write("production-grounding.json", EvalSnapshotDto.GROUNDING_TYPE, "grounding-old",
                groundingSnapshot("grounding-old", "2026-10-10T10:00:00+08:00"));
        // 最新历史即使 JSON 可解码，生成时间不能核验也必须跳过。
        EvalSnapshotDto.GroundingSnapshot invalid = groundingSnapshot("grounding-invalid", "不可核验的时间");
        String invalidJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                new EvalSnapshotDto.Envelope(EvalSnapshotDto.CURRENT_SCHEMA_VERSION,
                        EvalSnapshotDto.GROUNDING_TYPE, "grounding-invalid", "2026-10-11T10:00:00+08:00",
                        invalid));
        Files.createDirectories(directory);
        Files.writeString(store.historyPath("production-grounding.json", "grounding-invalid"), invalidJson);
        Files.writeString(old.currentPath(), "{");
        GroundingLiveEvalService service = new GroundingLiveEvalService(null, mapper, store);

        var report = service.current();

        assertThat(report.status()).isEqualTo("SNAPSHOT_CORRUPTED");
        assertThat(report.metrics()).isNull();
        assertThat(report.lastCompleted().status()).isEqualTo("COMPLETED");
        assertThat(report.lastCompleted().runId()).isEqualTo("grounding-old");
        assertThat(report.lastCompleted().finishedAt()).isEqualTo("2026-10-10T10:00:00+08:00");
        assertThat(report.lastCompleted().snapshotPath()).isEqualTo(old.historyPath().toString());
        assertThat(service.current().status()).isEqualTo("SNAPSHOT_CORRUPTED");
    }

    @Test
    void 检索报告版本错误不伪装完成且携带最后有效指标() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory);
        var old = store.write("production-retrieval.json", EvalSnapshotDto.RETRIEVAL_TYPE, "retrieval-old",
                retrievalSnapshot("retrieval-old", "2026-10-10T10:00:00+08:00"));
        Files.writeString(old.currentPath(), mapper.writeValueAsString(new EvalSnapshotDto.Envelope(99,
                EvalSnapshotDto.RETRIEVAL_TYPE, "retrieval-new", "2026-10-11T10:00:00+08:00", java.util.Map.of("status", "COMPLETED"))));
        KnowledgeCorpus corpus = mock(KnowledgeCorpus.class);
        when(corpus.documents()).thenReturn(List.of());
        ProductionRetrievalEvalService service = new ProductionRetrievalEvalService(null, corpus, mapper, store);

        var report = service.current();

        assertThat(report.status()).isEqualTo("VERSION_INCOMPATIBLE");
        assertThat(report.runId()).isEqualTo("retrieval-new");
        assertThat(report.available()).isFalse();
        assertThat(report.lastCompleted().runId()).isEqualTo("retrieval-old");
        assertThat(report.lastCompleted().generatedAt()).isEqualTo("2026-10-10T10:00:00+08:00");
        assertThat(report.lastCompleted().overall().hitRate()).isEqualTo(1);
        assertThat(report.lastCompleted().snapshotPath()).isEqualTo(old.historyPath().toString());
    }
}
