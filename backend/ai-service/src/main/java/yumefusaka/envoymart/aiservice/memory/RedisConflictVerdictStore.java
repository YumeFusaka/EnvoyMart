package yumefusaka.envoymart.aiservice.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.rag.ConflictVerdictStore;

import java.time.Duration;
import java.util.Optional;

/**
 * 冲突裁定的 Redis 持久化。
 * <p>
 * <b>为什么这个功能必须落盘。</b>用户拍板的原话是「正确的决策需要持久化」——而冲突裁定
 * 天然是跨会话的：同一份说明书里的同一处矛盾，用户这周问过一次、下周还会再问。
 * 留在进程内存里的裁定会随着重启蒸发，于是「已经裁定过的结论」每重启一次就作废一次，
 * 用户看到的是同一件事时好时坏。
 * <p>
 * <b>TTL 取 90 天，与文档更新节奏对齐。</b>裁定的有效期本质上是「所依据的资料没变」，
 * 而资料变化已经由指纹（证据正文哈希）兜住——指纹对不上就不会命中，与 TTL 无关。
 * TTL 在这里只做兜底回收：一份半年没人问过的裁定，留着也只会是历史包袱。
 * <p>
 * <b>读写的失败语义</b>：一律向上抛，由 {@code ConflictVerdictService} 兜住并降级为
 * 「本轮按首次判定处理」。裁定复用是增强项，不是对话的必需项——存储炸了不该让用户等不到回答。
 */
@Slf4j
@Component
public class RedisConflictVerdictStore implements ConflictVerdictStore {

    private static final String KEY_PREFIX = "conflict:verdict:";

    private static final Duration TTL = Duration.ofDays(90);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisConflictVerdictStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 存储载体 —— 与领域 record 解耦，字段名稳定，不受领域类重构影响。 */
    record StoredVerdict(String fingerprint, String detail, boolean resolved, long decidedAt) {

        static StoredVerdict of(Verdict verdict) {
            return new StoredVerdict(verdict.fingerprint(), verdict.detail(),
                    verdict.resolved(), verdict.decidedAt());
        }

        Verdict toVerdict() {
            return new Verdict(fingerprint, detail, resolved, decidedAt);
        }
    }

    @Override
    public Optional<Verdict> find(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        String raw = redis.opsForValue().get(KEY_PREFIX + fingerprint);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readValue(raw, StoredVerdict.class).toVerdict());
    }

    @Override
    public void save(Verdict verdict) {
        if (verdict == null || verdict.fingerprint() == null || verdict.fingerprint().isBlank()) {
            return;
        }
        redis.opsForValue().set(KEY_PREFIX + verdict.fingerprint(),
                objectMapper.writeValueAsString(StoredVerdict.of(verdict)), TTL);
    }
}
