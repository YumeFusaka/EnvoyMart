package yumefusaka.envoymart.aiservice.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.memory.ProfileEntry;
import yumefusaka.envoymart.agent.memory.ProfileRepository;
import yumefusaka.envoymart.agent.memory.ProfileSlot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 用户画像的 Redis 持久化。
 * <p>
 * <b>为什么需要它</b>：长期记忆分两轨，但持久化能力一度不对称——情节记忆写进向量库（持久），
 * 画像只在进程内存里（重启即丢）。后果是助手能说出「我记得你上次抱怨过物流慢」，
 * 却说不出「你的预算多少」，两轨对不上，用户会直接感到"它记性时好时坏"。
 * <p>
 * <b>为什么不直接序列化 {@link ProfileEntry}</b>：它是 Lombok 的 {@code @Data + @Builder} 类，
 * 加上的 {@code @NoArgsConstructor} 在编译后并没有落到字节码里（javap 只有一个 package-private
 * 的全参构造器），Jackson 于是报 {@code no Creators, like default constructor, exist}。
 * 与其去推演 Lombok 在多个构造器注解共存时的取舍，不如让<b>存储格式与领域对象解耦</b>：
 * 用一个 record 做载体，它的规范构造器是 Jackson 能明确识别的。顺带的好处是数据库里的
 * 字段名不再跟着领域类改。
 * <p>
 * <b>读写的失败语义</b>：一律向上抛，由 {@code UserProfileStore} 兜住并降级为纯内存，
 * 而不是让"画像存不上"变成"这次对话失败"。画像是增强项，不是必需项。
 */
@Slf4j
@Component
public class RedisProfileRepository implements ProfileRepository {

    private static final String KEY_PREFIX = "profile:";

    /**
     * 画像是长期事实，但不必永久保留：用户半年不再出现，留一份没有上限的副本没有意义。
     * 每次写入都会续期，所以持续使用的用户不会因为 TTL 丢画像。
     */
    private static final Duration TTL = Duration.ofDays(180);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisProfileRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 存储载体 —— 与领域对象解耦，字段名稳定。枚举按名字存取，避免依赖序号。 */
    record StoredEntry(String slot, String value, String previousValue, Instant updatedAt, double confidence) {

        static StoredEntry of(ProfileEntry entry) {
            return new StoredEntry(
                    entry.getSlot() == null ? null : entry.getSlot().name(),
                    entry.getValue(),
                    entry.getPreviousValue(),
                    entry.getUpdatedAt(),
                    entry.getConfidence());
        }

        ProfileEntry toEntry() {
            return ProfileEntry.builder()
                    .slot(slot == null ? null : ProfileSlot.valueOf(slot))
                    .value(value)
                    .previousValue(previousValue)
                    .updatedAt(updatedAt == null ? Instant.now() : updatedAt)
                    .confidence(confidence)
                    .build();
        }
    }

    @Override
    public List<ProfileEntry> load(String userId) {
        String raw = redis.opsForValue().get(KEY_PREFIX + userId);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<StoredEntry> stored = objectMapper.readValue(raw, new TypeReference<List<StoredEntry>>() {
        });
        return stored.stream()
                .map(StoredEntry::toEntry)
                .filter(entry -> entry.getSlot() != null)
                .toList();
    }

    @Override
    public void save(String userId, List<ProfileEntry> entries) {
        String key = KEY_PREFIX + userId;
        if (entries == null || entries.isEmpty()) {
            // 清空画像要真的把 key 删掉，写一个空数组等于留了个还会被读出来的空壳
            redis.delete(key);
            return;
        }
        List<StoredEntry> stored = entries.stream().map(StoredEntry::of).toList();
        redis.opsForValue().set(key, objectMapper.writeValueAsString(stored), TTL);
    }
}
