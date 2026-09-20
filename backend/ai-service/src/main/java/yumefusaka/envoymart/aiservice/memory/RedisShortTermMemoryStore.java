package yumefusaka.envoymart.aiservice.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.memory.MemoryItem;
import yumefusaka.envoymart.agent.memory.ShortTermMemoryStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话窗口的 Redis 持久化 —— 让短期记忆跨重启、跨实例。
 * <p>
 * <b>为什么需要它</b>：会话窗口原先只活在进程内存里，有两处硬伤——重启就丢，
 * 以及多实例时同一用户落到不同实例上下文直接断。项目的业务层是无状态的、可以水平扩，
 * AI 层却因为这一处内存状态扩不了，两者的不对称会让"支持分布式"这句话在架构上站不住。
 * <p>
 * <b>用 List 而不是 String</b>：滑动窗口的语义天然就是「从一端进、从另一端出」。
 * {@code LPUSH} + {@code LTRIM} 一条命令链就完成了写入与裁剪，读的时候 {@code LRANGE} 取窗口，
 * 不需要把整个会话读出来再自己裁——那在长会话上是 O(n) 的无效传输。
 * <p>
 * <b>读写失败一律吞掉</b>：会话窗口是增强项不是必需项。Redis 抖动时正确的降级是
 * 「这一轮没有历史上下文」，而不是「这次对话失败」——{@code Memory} 接口上的其他实现
 * 也是这个立场（画像仓库向上抛、由上层兜住降级为纯内存，情节记忆召回失败返回空）。
 * 差别在于这里没有上层可兜，所以降级就地完成。
 * <p>
 * <b>存储载体与领域对象解耦</b>：{@link MemoryItem} 是 Lombok 的 {@code @Data + @Builder} 类，
 * 直接序列化会踩 {@code @NoArgsConstructor} 不落字节码的坑（画像那边已经踩过一次）。
 * 用一个 record 做载体，字段名稳定，也不跟着领域类改。
 */
@Slf4j
@Component
public class RedisShortTermMemoryStore implements ShortTermMemoryStore {

    private static final String KEY_PREFIX = "stm:";

    /**
     * 会话窗口是短期上下文，不必永久保留：一个七天没再出现的会话，留着没有意义。
     * 每次写入都会续期，持续使用的会话不会因为 TTL 丢上下文。
     */
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisShortTermMemoryStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 存储载体 —— 枚举按名字存取，避免依赖序号。 */
    record StoredItem(String id, String userId, String content, String type, Instant timestamp) {

        static StoredItem of(MemoryItem item) {
            return new StoredItem(
                    item.getId(),
                    item.getUserId(),
                    item.getContent(),
                    item.getType() == null ? null : item.getType().name(),
                    item.getTimestamp());
        }

        MemoryItem toItem(String sessionId) {
            return MemoryItem.builder()
                    .id(id)
                    .userId(userId)
                    .sessionId(sessionId)
                    .content(content)
                    .type(type == null ? null : MemoryItem.Type.valueOf(type))
                    // 时间戳必须原样带回：曾经召回路径重新 builder 时不传它，
                    // 于是所有历史条目读出来都变成"此刻"，任何基于新鲜度的策略静默失真
                    .timestamp(timestamp == null ? Instant.now() : timestamp)
                    .build();
        }
    }

    @Override
    public List<MemoryItem> load(String sessionId, int limit) {
        try {
            List<String> raw = redis.opsForList().range(key(sessionId), 0, limit - 1L);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<MemoryItem> items = new ArrayList<>(raw.size());
            for (String json : raw) {
                items.add(objectMapper.readValue(json, StoredItem.class).toItem(sessionId));
            }
            // 写入是 LPUSH（新条目在头部），读出来是倒序，反转回时间正序
            Collections.reverse(items);
            return items;
        } catch (Exception e) {
            log.warn("[STM] 载入会话窗口失败，本轮按无历史上下文继续: session={} err={}",
                    sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void append(String sessionId, MemoryItem item, int limit) {
        try {
            String key = key(sessionId);
            redis.opsForList().leftPush(key, objectMapper.writeValueAsString(StoredItem.of(item)));
            // 裁剪与续期都在这里做：窗口上限和过期时间属于存储侧的约束，
            // 分散到调用方会让"窗口到底多长"有两个真相
            redis.opsForList().trim(key, 0, limit - 1L);
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.warn("[STM] 写入会话窗口失败，本轮上下文不落盘: session={} err={}",
                    sessionId, e.getMessage());
        }
    }

    @Override
    public void clear(String sessionId) {
        try {
            redis.delete(key(sessionId));
        } catch (Exception e) {
            log.warn("[STM] 清理会话窗口失败: session={} err={}", sessionId, e.getMessage());
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
