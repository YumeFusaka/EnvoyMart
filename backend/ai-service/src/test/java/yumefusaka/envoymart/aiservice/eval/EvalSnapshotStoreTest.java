package yumefusaka.envoymart.aiservice.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

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
