package yumefusaka.envoymart.aiservice.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalSnapshotStoreTest {

    @TempDir
    Path directory;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 写入使用当前文件和带_runId_的历史文件并可读回() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory);

        EvalSnapshotStore.WriteResult first = store.write("production-retrieval.json",
                EvalSnapshotDto.RETRIEVAL_TYPE, "run-20261010-a",
                Map.of("status", "COMPLETED", "answer", "历史可核验"));
        EvalSnapshotStore.WriteResult second = store.write("production-retrieval.json",
                EvalSnapshotDto.RETRIEVAL_TYPE, "run-20261010-b",
                Map.of("status", "COMPLETED", "answer", "最新结果"));

        EvalSnapshotStore.ReadResult<Map> read = store.read("production-retrieval.json", Map.class);
        assertThat(read.status()).isEqualTo(EvalSnapshotDto.Status.COMPLETED);
        assertThat(read.runId()).isEqualTo("run-20261010-b");
        assertThat(read.value()).containsEntry("answer", "最新结果");
        assertThat(first.historyPath()).exists();
        assertThat(second.historyPath()).exists();
        assertThat(Files.list(directory).noneMatch(path -> path.getFileName().toString().contains(".tmp-"))).isTrue();
    }

    @Test
    void 目录注入后写入不依赖工作目录() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory.resolve("nested").resolve("eval"));

        store.write("grounding.json", EvalSnapshotDto.GROUNDING_TYPE, "run-1",
                Map.of("status", "COMPLETED"));

        assertThat(directory.resolve("nested/eval/grounding.json")).exists();
        assertThat(store.currentPath("grounding.json")).isEqualTo(directory.resolve("nested/eval/grounding.json").toAbsolutePath());
    }

    @Test
    void 损坏快照返回损坏状态而不是_NEVER_RUN() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory);
        Files.createDirectories(directory);
        Files.writeString(store.currentPath("production-grounding.json"), "{\"status\":", StandardCharsets.UTF_8);

        EvalSnapshotStore.ReadResult<Map> read = store.read("production-grounding.json", Map.class);

        assertThat(read.status()).isEqualTo(EvalSnapshotDto.Status.SNAPSHOT_CORRUPTED);
        assertThat(read.value()).isNull();
        assertThat(read.error()).contains("Exception");
    }

    @Test
    void 高于当前版本的快照返回版本不兼容() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory);
        Files.createDirectories(directory);
        String json = mapper.writeValueAsString(new EvalSnapshotDto.Envelope(
                EvalSnapshotDto.CURRENT_SCHEMA_VERSION + 1, EvalSnapshotDto.GROUNDING_TYPE,
                "run-new", "2026-10-10T00:00:00Z", Map.of("status", "COMPLETED")));
        Files.writeString(store.currentPath("production-grounding.json"), json, StandardCharsets.UTF_8);

        EvalSnapshotStore.ReadResult<Map> read = store.read("production-grounding.json", Map.class);

        assertThat(read.status()).isEqualTo(EvalSnapshotDto.Status.VERSION_INCOMPATIBLE);
        assertThat(read.runId()).isEqualTo("run-new");
    }

    @Test
    void 旧版无外壳快照按逐字段兼容读取() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory);
        Files.createDirectories(directory);
        Files.writeString(store.currentPath("production-retrieval.json"),
                "{\"status\":\"COMPLETED\",\"answer\":\"旧结果\"}", StandardCharsets.UTF_8);

        EvalSnapshotStore.ReadResult<Map> read = store.read("production-retrieval.json", Map.class);

        assertThat(read.status()).isEqualTo(EvalSnapshotDto.Status.COMPLETED);
        assertThat(read.schemaVersion()).isEqualTo(1);
        assertThat(read.value()).containsEntry("answer", "旧结果");
    }

    @Test
    void 损坏当前保留错误并返回历史原始身份且不改写文件() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory);
        var saved = store.write("production-grounding.json", EvalSnapshotDto.GROUNDING_TYPE, "run-old",
                Map.of("status", "COMPLETED", "answer", "最后有效回答"));
        String history = Files.readString(saved.historyPath());
        Files.writeString(saved.currentPath(), "{损坏");

        var read = store.readWithHistory("production-grounding.json", EvalSnapshotDto.GROUNDING_TYPE, Map.class);

        assertThat(read.current().status()).isEqualTo(EvalSnapshotDto.Status.SNAPSHOT_CORRUPTED);
        assertThat(read.current().value()).isNull();
        assertThat(read.lastCompleted().runId()).isEqualTo("run-old");
        assertThat(read.lastCompleted().path()).isEqualTo(saved.historyPath());
        assertThat(read.lastCompleted().value()).containsEntry("answer", "最后有效回答");
        assertThat(Files.readString(saved.currentPath())).isEqualTo("{损坏");
        assertThat(Files.readString(saved.historyPath())).isEqualTo(history);
    }

    @Test
    void 不兼容当前保留新版本身份并回退兼容历史() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory);
        store.write("production-grounding.json", EvalSnapshotDto.GROUNDING_TYPE, "run-old",
                Map.of("status", "COMPLETED"));
        Files.writeString(store.currentPath("production-grounding.json"), mapper.writeValueAsString(
                new EvalSnapshotDto.Envelope(99, EvalSnapshotDto.GROUNDING_TYPE, "run-new",
                        "2026-10-11T00:00:00Z", Map.of("status", "COMPLETED"))));

        var read = store.readWithHistory("production-grounding.json", EvalSnapshotDto.GROUNDING_TYPE, Map.class);

        assertThat(read.current().status()).isEqualTo(EvalSnapshotDto.Status.VERSION_INCOMPATIBLE);
        assertThat(read.current().runId()).isEqualTo("run-new");
        assertThat(read.lastCompleted().runId()).isEqualTo("run-old");
    }

    @Test
    void 坏历史类型版本身份状态和临时文件不能替代有效历史() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory);
        store.write("production-grounding.json", EvalSnapshotDto.GROUNDING_TYPE, "run-old",
                Map.of("status", "COMPLETED"));
        for (String run : java.util.List.of("bad-json", "bad-type", "bad-version", "bad-state", "bad-id", "bad-date")) {
            String json = mapper.writeValueAsString(new EvalSnapshotDto.Envelope(
                    run.equals("bad-version") ? 99 : 2,
                    run.equals("bad-type") ? EvalSnapshotDto.RETRIEVAL_TYPE : EvalSnapshotDto.GROUNDING_TYPE,
                    run.equals("bad-id") ? "other" : run,
                    run.equals("bad-date") ? "不是时间" : "2099-01-01T00:00:00Z",
                    Map.of("status", run.equals("bad-state") ? "FAILED" : "COMPLETED")));
            Files.writeString(store.historyPath("production-grounding.json", run), run.equals("bad-json") ? "{" : json);
        }
        Files.writeString(directory.resolve("production-grounding-run-later.json.tmp-interrupted"), "{\"status\":\"COMPLETED\"}");
        Files.writeString(store.currentPath("production-grounding.json"), "{");

        var read = store.readWithHistory("production-grounding.json", EvalSnapshotDto.GROUNDING_TYPE, Map.class);

        assertThat(read.lastCompleted().runId()).isEqualTo("run-old");
    }

    @Test
    void 恢复按真实写入时间排序不依赖文件修改时间和时区字符串() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory);
        Files.writeString(store.historyPath("grounding.json", "older"), mapper.writeValueAsString(
                new EvalSnapshotDto.Envelope(2, EvalSnapshotDto.GROUNDING_TYPE, "older",
                        "2026-10-11T08:00:00+08:00", Map.of("status", "COMPLETED"))));
        Files.writeString(store.historyPath("grounding.json", "newer"), mapper.writeValueAsString(
                new EvalSnapshotDto.Envelope(2, EvalSnapshotDto.GROUNDING_TYPE, "newer",
                        "2026-10-11T01:00:00Z", Map.of("status", "COMPLETED"))));
        Files.setLastModifiedTime(store.historyPath("grounding.json", "older"), FileTime.fromMillis(4102444800000L));
        Files.writeString(store.currentPath("grounding.json"), "{");

        assertThat(store.readWithHistory("grounding.json", EvalSnapshotDto.GROUNDING_TYPE, Map.class)
                .lastCompleted().runId()).isEqualTo("newer");
    }

    @Test
    void 没有合格历史时损坏状态和空结果保持明确() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory);
        Files.writeString(store.currentPath("grounding.json"), "{");

        var read = store.readWithHistory("grounding.json", EvalSnapshotDto.GROUNDING_TYPE, Map.class);

        assertThat(read.current().status()).isEqualTo(EvalSnapshotDto.Status.SNAPSHOT_CORRUPTED);
        assertThat(read.current().error()).isNotBlank();
        assertThat(read.lastCompleted()).isNull();
    }

    @Test
    void 快照路径不能逃出配置目录且同名目录不是历史文件() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory);
        for (String file : java.util.List.of("..", ".", "../outside.json", "nested/file.json")) {
            assertThatThrownBy(() -> store.currentPath(file)).isInstanceOf(IllegalArgumentException.class);
        }
        Files.createDirectories(store.historyPath("grounding.json", "directory"));
        Files.writeString(store.currentPath("grounding.json"), "{");

        assertThat(store.readWithHistory("grounding.json", EvalSnapshotDto.GROUNDING_TYPE, Map.class)
                .lastCompleted()).isNull();
    }

    @Test
    void current替换前中断留下的临时文件不丢最后有效快照() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory);
        var old = store.write("grounding.json", EvalSnapshotDto.GROUNDING_TYPE, "old",
                Map.of("status", "COMPLETED", "answer", "旧结果"));
        String original = Files.readString(old.currentPath());
        Files.writeString(old.currentPath().resolveSibling("grounding.json.tmp-interrupted"), "{半份JSON");

        var read = store.readWithHistory("grounding.json", EvalSnapshotDto.GROUNDING_TYPE, Map.class);

        assertThat(read.current().status()).isEqualTo(EvalSnapshotDto.Status.COMPLETED);
        assertThat(read.current().runId()).isEqualTo("old");
        assertThat(read.current().value()).containsEntry("answer", "旧结果");
        assertThat(Files.readString(old.currentPath())).isEqualTo(original);
        assertThat(old.historyPath()).exists();
    }

    @Test
    void 实际写入在current替换前中断仍保留旧current和新历史() throws Exception {
        EvalSnapshotStore store = new EvalSnapshotStore(mapper, directory);
        var old = store.write("grounding.json", EvalSnapshotDto.GROUNDING_TYPE, "old",
                Map.of("status", "COMPLETED", "answer", "旧结果"));
        String current = Files.readString(old.currentPath());
        EvalSnapshotStore interrupted = new EvalSnapshotStore(mapper, directory, (temporary, target) -> {
            if (target.equals(old.currentPath())) throw new java.io.IOException("模拟current替换前中断");
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        });

        assertThatThrownBy(() -> interrupted.write("grounding.json", EvalSnapshotDto.GROUNDING_TYPE,
                "new", Map.of("status", "COMPLETED", "answer", "新结果")))
                .isInstanceOf(java.io.IOException.class).hasMessage("模拟current替换前中断");

        assertThat(Files.readString(old.currentPath())).isEqualTo(current);
        assertThat(store.read("grounding.json", Map.class).runId()).isEqualTo("old");
        assertThat(store.historyPath("grounding.json", "new")).exists();
        Files.writeString(old.currentPath(), "{");
        assertThat(store.readWithHistory("grounding.json", EvalSnapshotDto.GROUNDING_TYPE, Map.class)
                .lastCompleted().runId()).isEqualTo("new");
    }

    @Test
    void 现存生产快照可用专用_DTO_跨重启读取() {
        Path source = Paths.get("data", "eval");
        if (!Files.exists(source.resolve("production-retrieval.json"))) {
            return;
        }
        EvalSnapshotStore retrievalStore = new EvalSnapshotStore(mapper, source);
        EvalSnapshotStore.ReadResult<EvalSnapshotDto.RetrievalSnapshot> retrieval = retrievalStore.read(
                "production-retrieval.json", EvalSnapshotDto.RETRIEVAL_TYPE, EvalSnapshotDto.RetrievalSnapshot.class);
        assertThat(retrieval.status()).isEqualTo(EvalSnapshotDto.Status.COMPLETED);
        assertThat(retrieval.value()).isNotNull();
        assertThat(retrieval.value().cases()).isNotEmpty();

        if (Files.exists(source.resolve("production-grounding.json"))) {
            EvalSnapshotStore groundingStore = new EvalSnapshotStore(mapper, source);
            EvalSnapshotStore.ReadResult<EvalSnapshotDto.GroundingSnapshot> grounding = groundingStore.read(
                    "production-grounding.json", EvalSnapshotDto.GROUNDING_TYPE, EvalSnapshotDto.GroundingSnapshot.class);
            assertThat(grounding.status()).isEqualTo(EvalSnapshotDto.Status.COMPLETED);
            assertThat(grounding.value()).isNotNull();
            assertThat(grounding.value().cases()).isNotEmpty();
        }
    }
}
