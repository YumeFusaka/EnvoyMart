package yumefusaka.envoymart.aiservice.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 图谱构建逐条失败账本。汇总与管理端查询都从同一份记录读取。 */
@Slf4j
@Component
public class GraphFailureStore {
    private static final String PREFIX = "knowledge:graph:failure:";
    private static final String INDEX = "knowledge:graph:failures";
    private static final Duration TTL = Duration.ofDays(30);
    public enum Phase { EXTRACT, NORMALIZE, LINK, RELATION_VALIDATE, WRITE }
    public enum Reason { EMPTY_CONTENT, MODEL_ERROR, INVALID_JSON, PRODUCT_NOT_FOUND, UNDECLARED_SUBJECT, WRITE_ERROR, CATALOG_UNAVAILABLE }
    public record Failure(String id, String batchId, String docNo, String subject, Phase phase, Reason reason,
                          String detail, Instant at, boolean retryable) {}
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    public GraphFailureStore(StringRedisTemplate redis, ObjectMapper mapper) { this.redis = redis; this.mapper = mapper; }
    public String beginBatch() { return "graph-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8); }
    public void record(String batchId, String docNo, String subject, Phase phase, Reason reason, String detail, boolean retryable) {
        Failure value = new Failure(UUID.randomUUID().toString(), batchId, docNo, subject, phase, reason, sanitize(detail), Instant.now(), retryable);
        try {
            String key = PREFIX + value.id();
            redis.opsForValue().set(key, mapper.writeValueAsString(value), TTL);
            redis.opsForList().leftPush(INDEX, key);
            redis.opsForList().trim(INDEX, 0, 999);
            redis.expire(INDEX, TTL);
            log.info("[GraphFailure] batch={} doc={} phase={} reason={} retryable={}", batchId, docNo, phase, reason, retryable);
        } catch (Exception e) { log.warn("[GraphFailure] 记录失败 batch={} doc={} err={}", batchId, docNo, e.getMessage()); }
    }
    public List<Failure> list(String batchId, String docNo, Phase phase, Reason reason, int limit) {
        List<String> keys = redis.opsForList().range(INDEX, 0, Math.max(0, Math.min(limit, 100)) - 1L);
        if (keys == null) return List.of();
        List<Failure> result = new ArrayList<>();
        for (String key : keys) try {
            String raw = redis.opsForValue().get(key); if (raw == null) continue;
            Failure f = mapper.readValue(raw, Failure.class);
            if ((batchId == null || batchId.equals(f.batchId())) && (docNo == null || docNo.equals(f.docNo())) &&
                    (phase == null || phase == f.phase()) && (reason == null || reason == f.reason())) result.add(f);
        } catch (Exception ignored) { }
        return result;
    }
    private String sanitize(String value) { return value == null ? null : value.replaceAll("[\\r\\n\\t]", " ").substring(0, Math.min(value.length(), 500)); }
}
