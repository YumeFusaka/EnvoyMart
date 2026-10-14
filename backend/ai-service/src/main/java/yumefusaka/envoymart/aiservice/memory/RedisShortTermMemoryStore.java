package yumefusaka.envoymart.aiservice.memory;

import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    /**
     * 降级计数。写失败原先只有一行 WARN —— 日志适合排查"这一次发生了什么"，
     * 却回答不了"最近是不是一直在降级"。Redis 抖动时用户只会觉得"它怎么不记得了"，
     * 没有指标就没有任何可观测的迹象，也没有告警的挂点。
     * <p>
     * 可为 null（单测里直接 new 时不注入），此时不计数、不影响功能。
     */
    private final MeterRegistry meterRegistry;

    public RedisShortTermMemoryStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(redis, objectMapper, null);
    }

    /**
     * Spring 注入用的构造器。
     * <p>
     * <b>必须显式标注。</b>这里有两个构造器（两参的那个是给单测直接 new 的），
     * 不标 {@code @Autowired} 时 Spring 无法在多个候选里选一个，
     * 报的是 {@code No default constructor found} —— 一个听起来像「缺无参构造」、
     * 实际是「构造器有歧义」的错误，很容易往错的方向排查。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RedisShortTermMemoryStore(StringRedisTemplate redis, ObjectMapper objectMapper,
                                     MeterRegistry meterRegistry) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记一次降级。{@code op} 区分读与写——读失败意味着"这一轮没有历史上下文"，
     * 写失败意味着"这一轮的话没有留档"，两者的影响面不同，合成一个计数就分不开了。
     */
    private void recordDegraded(String op) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("agent.stm.degraded")
                .tag("op", op)
                .description("会话窗口在 Redis 上读写失败而降级的次数")
                .register(meterRegistry)
                .increment();
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
            recordDegraded("load");
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
            recordDegraded("append");
        }
    }

    /**
     * 重新生成：**丢弃**窗口里最后一轮，让本轮对模型回到「首问」的形态。
     * <p>
     * 窗口是 LPUSH（新条目在头部）、读出时反转回时间正序，所以「最后一轮」位于
     * <b>列表头部</b>：头部是最后一条助手答复，紧随其后的第二条是它对应的用户消息。
     * <p>
     * <b>为什么是「丢」而不是「换成新的一轮」。</b>调用时机在<b>生成之前</b>——
     * 那时新答复还不存在。只追加不丢弃时，模型读到的历史最后两条正是
     * 「用户问了这句话 → 我答过一版」，于是它把本轮读成「同一个问题又问一次」，
     * 转而点评自己上一版（实测产出一段**编造的忏悔**：「我并没有真正检索到这些条款」，
     * 而知识库确实有那些条款）。丢掉之后本轮就是一次干净的首问，
     * 新答复在生成结束后按正常路径 append，窗口形状与 {@code chat:hist:} 一致。
     * <p>
     * <b>判据看内容而不是条数</b>：头部那条必须真的是助手答复才连丢两条。
     * 窗口可能停在任何位置（上一轮只落了用户消息、或被取消而只落了半条），
     * 按条数硬丢会误删上一轮的正经内容——宁可少丢。
     * <p>
     * <b>TTL 不续</b>：丢弃不是「这一轮发生过对话」的证据。重新生成若在生成阶段
     * 失败，续期会让一个已经没人用的会话窗口又活一遍；真正的续期交给随后的 append。
     * @return true 表示确实丢掉了完整一轮；false 表示窗口里凑不齐一轮
     */
    @Override
    public boolean dropLastTurn(String sessionId) {
        try {
            String key = key(sessionId);
            List<String> head = redis.opsForList().range(key, 0, 1);
            if (head == null || head.isEmpty()) {
                return false;
            }
            boolean paired = head.size() >= 2 && isAssistantReply(head.get(0));
            redis.opsForList().trim(key, paired ? 2 : 1, -1);
            return paired;
        } catch (Exception e) {
            log.warn("[STM] 丢弃会话窗口最后一轮失败，本轮上下文按原样继续: session={} err={}",
                    sessionId, e.getMessage());
            recordDegraded("drop");
            return false;
        }
    }

    private boolean isAssistantReply(String json) {
        try {
            String content = objectMapper.readValue(json, StoredItem.class).content();
            return content != null && content.startsWith("assistant:");
        } catch (Exception e) {
            return false;
        }
    }

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
