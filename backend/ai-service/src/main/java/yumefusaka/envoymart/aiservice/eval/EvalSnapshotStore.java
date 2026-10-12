package yumefusaka.envoymart.aiservice.eval;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * 评测快照文件存储。
 *
 * <p>快照先写同目录临时文件，再替换当前文件。进程崩溃最多留下临时文件，
 * 不会留下半份 JSON；每次完成还会保留一份带 runId 的历史副本，便于排查和回看。</p>
 */
@Component
public final class EvalSnapshotStore {

    public static final String DEFAULT_DIRECTORY = "data/eval";

    private final Path directory;
    private final ObjectMapper objectMapper;
    private final SnapshotMover mover;

    @Autowired
    public EvalSnapshotStore(ObjectMapper objectMapper,
                             @Value("${eval.snapshot-dir:${EVAL_SNAPSHOT_DIR:data/eval}}") String directory) {
        this(objectMapper, Path.of(directory == null || directory.isBlank() ? DEFAULT_DIRECTORY : directory));
    }

    public EvalSnapshotStore(ObjectMapper objectMapper, Path directory) {
        this(objectMapper, directory, EvalSnapshotStore::atomicMove);
    }

    EvalSnapshotStore(ObjectMapper objectMapper, Path directory, SnapshotMover mover) {
        this.objectMapper = objectMapper;
        this.directory = (directory == null ? Path.of(DEFAULT_DIRECTORY) : directory).toAbsolutePath().normalize();
        this.mover = mover;
    }

    public Path directory() {
        return directory;
    }

    public Path currentPath(String fileName) {
        return directory.resolve(safeFileName(fileName));
    }

    public Path historyPath(String fileName, String runId) {
        String base = safeFileName(fileName);
        String suffix = sanitizeRunId(runId);
        int extension = base.lastIndexOf('.');
        String history = extension < 0
                ? base + "-" + suffix
                : base.substring(0, extension) + "-" + suffix + base.substring(extension);
        return directory.resolve(history);
    }

    /**
     * 读取当前快照。文件不存在是 NEVER_RUN；JSON 损坏和 schema 不兼容分别返回错误态，
     * 不会被调用方误认为是空结果。
     */
    public <T> ReadResult<T> read(String fileName, Class<T> payloadType) {
        return read(fileName, null, payloadType);
    }

    public <T> ReadResult<T> read(String fileName, String expectedSnapshotType, Class<T> payloadType) {
        return readPath(currentPath(fileName), expectedSnapshotType, payloadType, false);
    }

    /** 当前文件失败时读取最后一份可信的已完成历史；不会把当前错误吞掉。 */
    public <T> Recovery<T> readWithHistory(String fileName, String expectedSnapshotType, Class<T> payloadType) {
        ReadResult<T> current = read(fileName, expectedSnapshotType, payloadType);
        if (current.status() != EvalSnapshotDto.Status.SNAPSHOT_CORRUPTED
                && current.status() != EvalSnapshotDto.Status.VERSION_INCOMPATIBLE) {
            return new Recovery<>(current, null);
        }
        Optional<ReadResult<T>> history = findLastCompletedHistory(fileName, expectedSnapshotType, payloadType);
        return new Recovery<>(current, history.orElse(null));
    }

    private <T> Optional<ReadResult<T>> findLastCompletedHistory(String fileName, String expectedSnapshotType,
                                                                  Class<T> payloadType) {
        String base = safeFileName(fileName);
        int extension = base.lastIndexOf('.');
        String prefix = extension < 0 ? base + "-" : base.substring(0, extension) + "-";
        String suffix = extension < 0 ? "" : base.substring(extension);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            return java.util.stream.StreamSupport.stream(entries.spliterator(), false)
                    .filter(path -> isTrustedHistoryPath(path, prefix, suffix))
                    .map(path -> readPath(path, expectedSnapshotType, payloadType, true))
                    .filter(result -> result.status() == EvalSnapshotDto.Status.COMPLETED && result.value() != null)
                    .filter(result -> !(result.value() instanceof EvalSnapshotDto.GroundingSnapshot snapshot)
                            || snapshot.hasUsableAnswer())
                    .filter(result -> result.writtenAt() != null && result.runId() != null
                            && result.path().equals(historyPath(fileName, result.runId())))
                    .max(Comparator.comparing(result -> OffsetDateTime.parse(result.writtenAt()).toInstant()));
        } catch (IOException | DirectoryIteratorException ignored) {
            return Optional.empty();
        }
    }

    private static boolean isTrustedHistoryPath(Path path, String prefix, String suffix) {
        try {
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) return false;
            String name = path.getFileName().toString();
            return name.startsWith(prefix) && name.endsWith(suffix) && !name.contains(".tmp-")
                    && name.length() > prefix.length() + suffix.length();
        } catch (Exception ignored) {
            return false;
        }
    }

    private <T> ReadResult<T> readPath(Path path, String expectedSnapshotType, Class<T> payloadType,
                                        boolean requireEnvelope) {
        if (!Files.exists(path)) {
            return ReadResult.never(path);
        }
        String parsedRunId = null;
        try {
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                return ReadResult.corrupted(path, null, "快照路径不是可信普通文件");
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                return ReadResult.corrupted(path, null, "快照根节点不是 JSON 对象");
            }
            boolean envelope = root.has("payload") && root.has("snapshotType");
            if (requireEnvelope && !envelope) {
                return ReadResult.corrupted(path, textOrNull(root, "runId"), "历史快照缺少受信任外壳");
            }
            int schemaVersion = root.has("schemaVersion") ? root.get("schemaVersion").asInt() : 1;
            String runId = textOrNull(root, "runId");
            parsedRunId = runId;
            if (requireEnvelope && (!root.has("schemaVersion") || !root.get("schemaVersion").isIntegralNumber()
                    || runId == null || runId.isBlank() || !runId.equals(sanitizeRunId(runId)))) {
                return ReadResult.corrupted(path, runId, "历史快照版本或身份字段无效");
            }
            if (schemaVersion < 1 || schemaVersion > EvalSnapshotDto.CURRENT_SCHEMA_VERSION) {
                return ReadResult.versionIncompatible(path, runId,
                        "快照版本 " + schemaVersion + " 不在当前支持范围 1.."
                                + EvalSnapshotDto.CURRENT_SCHEMA_VERSION);
            }
            String snapshotType = textOrNull(root, "snapshotType");
            if (requireEnvelope && (expectedSnapshotType == null || !expectedSnapshotType.equals(snapshotType))) {
                return ReadResult.versionIncompatible(path, runId, "历史快照类型不匹配");
            }
            if (expectedSnapshotType != null && snapshotType != null
                    && !expectedSnapshotType.equals(snapshotType)) {
                return ReadResult.versionIncompatible(path, runId,
                        "快照类型 " + snapshotType + " 与读取方期望的 " + expectedSnapshotType + " 不匹配");
            }
            JsonNode payload = envelope ? root.get("payload") : root;
            T value = objectMapper.readValue(objectMapper.writeValueAsString(payload), payloadType);
            if (value instanceof EvalSnapshotDto.SnapshotPayload snapshot) snapshot.validate();
            String status = payload != null && payload.has("status") ? payload.get("status").asText() : null;
            EvalSnapshotDto.Status parsedStatus = normalizeStatus(status);
            if (requireEnvelope && !"COMPLETED".equals(status)) {
                return ReadResult.corrupted(path, runId, "历史快照不是已完成报告");
            }
            String payloadRunId = textOrNull(payload, "runId");
            if (requireEnvelope && payloadRunId != null && !payloadRunId.equals(runId)) {
                return ReadResult.corrupted(path, runId, "历史快照身份与外壳不匹配");
            }
            if (parsedStatus == EvalSnapshotDto.Status.SNAPSHOT_CORRUPTED) {
                return ReadResult.corrupted(path, runId, "快照包含未知状态：" + status);
            }
            String writtenAt = textOrNull(root, "writtenAt");
            if (requireEnvelope && !isValidWrittenAt(writtenAt)) {
                return ReadResult.corrupted(path, runId, "历史快照 writtenAt 无效");
            }
            return ReadResult.completed(path, runId, value, parsedStatus, schemaVersion, writtenAt);
        } catch (Exception e) {
            return ReadResult.corrupted(path, parsedRunId, e.getClass().getSimpleName() + ": " + safeMessage(e));
        }
    }

    private static boolean isValidWrittenAt(String value) {
        if (value == null || value.isBlank()) return false;
        try { OffsetDateTime.parse(value); return true; }
        catch (DateTimeParseException ignored) { return false; }
    }

    /** 写当前快照并保留同一 runId 的历史副本。 */
    public WriteResult write(String fileName, String snapshotType, String runId, Object payload) throws IOException {
        if (payload == null) {
            throw new IllegalArgumentException("快照 payload 不能为空");
        }
        String effectiveRunId = runId == null || runId.isBlank() ? UUID.randomUUID().toString() : runId;
        EvalSnapshotDto.Envelope envelope = new EvalSnapshotDto.Envelope(
                EvalSnapshotDto.CURRENT_SCHEMA_VERSION, snapshotType, effectiveRunId,
                java.time.OffsetDateTime.now().toString(), payload);
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
        Files.createDirectories(directory);
        Path history = historyPath(fileName, effectiveRunId);
        Path current = currentPath(fileName);
        atomicWrite(history, json);
        atomicWrite(current, json);
        return new WriteResult(current, history, effectiveRunId);
    }

    private void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            mover.move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void atomicMove(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    interface SnapshotMover {
        void move(Path temporary, Path target) throws IOException;
    }

    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("快照文件名不能为空");
        }
        Path path = Path.of(fileName);
        if (path.isAbsolute() || ".".equals(fileName) || "..".equals(fileName)
                || path.getNameCount() != 1 || !fileName.equals(path.getFileName().toString())) {
            throw new IllegalArgumentException("快照文件名必须是当前目录下的文件：" + fileName);
        }
        return fileName;
    }

    private static String sanitizeRunId(String runId) {
        String value = runId == null || runId.isBlank() ? UUID.randomUUID().toString() : runId;
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String textOrNull(JsonNode node, String name) {
        if (node == null) return null;
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static EvalSnapshotDto.Status normalizeStatus(String status) {
        if (status == null || status.isBlank() || "NEVER".equals(status) || "IDLE".equals(status)) {
            return EvalSnapshotDto.Status.NEVER_RUN;
        }
        try {
            EvalSnapshotDto.Status parsed = EvalSnapshotDto.Status.valueOf(status);
            return parsed == EvalSnapshotDto.Status.VERSION_INCOMPATIBLE
                    ? EvalSnapshotDto.Status.VERSION_INCOMPATIBLE : parsed;
        } catch (IllegalArgumentException ignored) {
            return EvalSnapshotDto.Status.SNAPSHOT_CORRUPTED;
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "无错误详情" : message;
    }

    public record WriteResult(Path currentPath, Path historyPath, String runId) {
    }

    public record Recovery<T>(ReadResult<T> current, ReadResult<T> lastCompleted) {
    }

    public record ReadResult<T>(EvalSnapshotDto.Status status, T value, String runId,
                               Path path, String error, int schemaVersion, String writtenAt) {
        static <T> ReadResult<T> never(Path path) {
            return new ReadResult<>(EvalSnapshotDto.Status.NEVER_RUN, null, null, path, null, 0, null);
        }

        static <T> ReadResult<T> completed(Path path, String runId, T value,
                                           EvalSnapshotDto.Status status, int schemaVersion, String writtenAt) {
            return new ReadResult<>(status == EvalSnapshotDto.Status.NEVER_RUN
                    ? EvalSnapshotDto.Status.COMPLETED : status, value, runId, path, null, schemaVersion, writtenAt);
        }

        static <T> ReadResult<T> corrupted(Path path, String runId, String error) {
            return new ReadResult<>(EvalSnapshotDto.Status.SNAPSHOT_CORRUPTED, null, runId, path, error, 0, null);
        }

        static <T> ReadResult<T> versionIncompatible(Path path, String runId, String error) {
            return new ReadResult<>(EvalSnapshotDto.Status.VERSION_INCOMPATIBLE, null, runId, path, error, 0, null);
        }
    }
}
