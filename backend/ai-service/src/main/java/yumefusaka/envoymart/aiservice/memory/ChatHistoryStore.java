package yumefusaka.envoymart.aiservice.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 会话历史的持久化 —— 让「多会话可切换」这件事有地方落。
 * <p>
 * <b>为什么放 Redis 而不是 MySQL</b>：历史与短期记忆窗口（{@link RedisShortTermMemoryStore}）
 * 是同一段生命周期 —— 一段会话七天不再出现就该消失，两者的 TTL、清理时机、
 * 归属判断完全重合。拆到两个存储里，就会出现「历史还在、上下文没了」这种半截状态，
 * 以及两套删除路径要各自维护。ai-service 目前也没有数据源，为一份缓存性质的数据
 * 引入数据库连接是给它加一条与业务层相反的生命周期。
 * <p>
 * <b>三个键分工</b>：
 * <ul>
 *   <li>{@code chat:hist:{userId}:{sessionId}} —— LIST，消息本体。LPUSH 进、LRANGE 出，与窗口语义同构</li>
 *   <li>{@code chat:meta:{userId}:{sessionId}} —— HASH，标题与创建时间。标题只在第一条消息时落一次（HSETNX），
 *       与「消息列表的第一条」解耦：翻页截断、消息被裁掉都不会让标题变样</li>
 *   <li>{@code chat:sessions:{userId}} —— ZSET，会话索引（member=sessionId，score=最后活跃时间）。
 *       侧栏按它倒序，不需要 SCAN，也不会随会话数变多而变慢</li>
 * </ul>
 * <p>
 * <b>归属隔离靠键命名空间而不是先查后判</b>：三个键都以 userId 打头，别人的 sessionId
 * 传进来只会命中自己名下不存在的键。这比「查出会话再比对 owner」少一次往返，
 * 也少一个忘记比对的分支 —— 那种分支的代价是越权读取。
 * <p>
 * <b>失败立场与窗口存储不同</b>：写入失败参照 {@code RedisShortTermMemoryStore} 吞掉并告警
 * （历史是增强项，不能因为它让整轮对话失败）；<b>读取与删除必须上抛</b> ——
 * 列表读失败若被吞成空列表，用户看到的是「我的会话全没了」，
 * 删除失败若被吞成成功，用户刷新后会发现「删掉的会话又回来了」。两者都是界面在撒谎。
 */
@Slf4j
@Component
public class ChatHistoryStore {

    private static final String HIST_PREFIX = "chat:hist:";
    private static final String META_PREFIX = "chat:meta:";
    private static final String INDEX_PREFIX = "chat:sessions:";
    private static final String DELETED_PREFIX = "chat:deleted:";

    /** 与短期记忆窗口同一档 TTL：一段会话的生命周期只有一个真相 */
    private static final Duration TTL = Duration.ofDays(7);

    /** 单会话消息上限。超出裁掉最旧的 —— 侧栏会话是「最近聊过什么」，不是档案库 */
    private static final int MAX_MESSAGES = 200;

    /**
     * 一条消息的存储载体。
     * <p>
     * 用 record 而不是直接序列化领域对象：{@code ChatResponse} 是 Lombok
     * {@code @Builder} 类且没有无参构造，Jackson 写得出去、读不回来 ——
     * 这个坑在画像仓库已经踩过一次（见 {@code RedisShortTermMemoryStore} 的注释）。
     * <p>
     * {@code response} 装的是当轮 {@code ChatResponse} 的通用 Map 形态（引用、工具轨迹、
     * 用量等）：字段随响应演进自动跟随，不必在这里再抄一份 DTO 定义。
     */
    public record StoredMessage(String id, String role, String content, Instant at, Map<String, Object> response) {
    }

    /** 侧栏一行 */
    public record SessionSummary(String sessionId, String title, Instant createdAt, Instant updatedAt, long messageCount) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ChatHistoryStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 记一轮对话：用户消息 + 助手消息。
     * <p>
     * 一次调用写两条而不是两次调用：两条消息共用一次裁剪、一次续期、一次索引更新，
     * 中途失败也不会留下「有问无答」的半轮孤本。
     */
    public void recordTurn(String userId, String sessionId, String userMessage,
                           String assistantReply, Object assistantPayload) {
        Instant now = Instant.now();
        List<StoredMessage> messages = List.of(
                new StoredMessage("u-" + UUID.randomUUID(), "user", userMessage, now, null),
                new StoredMessage("a-" + UUID.randomUUID(), "assistant",
                        assistantReply == null ? "" : assistantReply, now, toMap(assistantPayload)));
        write(userId, sessionId, messages);
    }

    /**
     * 只记助手答复：就地改写最后一条，而不是追加一轮（「重新生成」落历史的形态）。
     * <p>
     * 判据取「列表最新的一条是助手消息」，而不是「盲改 index 0」：改写的前提是
     * 上一轮确实留下过一份答复。最新一条不是助手消息（半轮孤本、异常状态）时
     * 退回<b>追加</b>——宁可多一条，也不能把一条用户消息覆盖成助手消息。
     * <p>
     * 最新一条在 index 0：写入是 LPUSH（新在头），{@link #loadMessages} 读出时
     * 才反转为时间正序。
     */
    public void recordAnswer(String userId, String sessionId, String assistantReply, Object assistantPayload) {
        StoredMessage answer = new StoredMessage("a-" + UUID.randomUUID(), "assistant",
                assistantReply == null ? "" : assistantReply, Instant.now(), toMap(assistantPayload));
        if (!rewriteLatestAssistant(userId, sessionId, answer)) {
            // 退回普通追加的路径：共用 write 的墓碑拦截、裁剪、续期与索引更新
            write(userId, sessionId, List.of(answer));
        }
    }

    /** @return true 表示已处理（改写完成、或会话已删除无需再写）；false 表示头部不是助手消息，应改为追加 */
    private boolean rewriteLatestAssistant(String userId, String sessionId, StoredMessage answer) {
        // 与 write 同一道闸：会话已删除时这一轮的生成可能还在跑，跑完不能把它写回来
        if (isDeleted(userId, sessionId)) {
            log.info("[History] 会话已删除，丢弃本次改写: session={}", sessionId);
            return true;
        }
        String histKey = histKey(userId, sessionId);
        String metaKey = metaKey(userId, sessionId);
        String indexKey = indexKey(userId);
        try {
            List<String> head = redis.opsForList().range(histKey, 0, 0);
            if (head == null || head.isEmpty()) {
                return false;
            }
            StoredMessage latest;
            try {
                latest = objectMapper.readValue(head.get(0), StoredMessage.class);
            } catch (Exception e) {
                // 头部这条读不出来，就无法确认它是不是助手消息 —— 不赌，走追加
                return false;
            }
            if (!"assistant".equals(latest.role())) {
                return false;
            }

            String payload = serialize(answer);
            long score = Instant.now().toEpochMilli();
            redis.execute(new SessionCallback<Void>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public <K, V> Void execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> ops = (RedisOperations<String, String>) (RedisOperations) operations;
                    ops.multi();
                    ops.opsForList().set(histKey, 0, payload);
                    ops.expire(histKey, TTL);
                    // 重新生成把会话顶到侧栏最前：用户刚刚用过它
                    ops.opsForZSet().add(indexKey, sessionId, score);
                    ops.expire(indexKey, TTL);
                    // 只续期不创建：元数据不存在时（异常状态）不该由一次改写来凭空立一个标题，
                    // EXPIRE 落在不存在的键上是空操作
                    ops.expire(metaKey, TTL);
                    ops.exec();
                    return null;
                }
            });

            // 与 write 相同的墓碑复查：删除与改写撞在同一瞬，上面那次 EXEC 会把会话写回来
            if (isDeleted(userId, sessionId)) {
                redis.delete(List.of(histKey, metaKey));
                redis.opsForZSet().remove(indexKey, sessionId);
                log.info("[History] 改写期间会话被删除，已回滚本次写入: session={}", sessionId);
            }
            return true;
        } catch (Exception e) {
            // 与 write 的失败立场一致：历史是增强项，失败告警不抛。这里不再退回追加——
            // 改写失败后追加等于「一次重新生成留下两条答复」，比少写更隐蔽
            log.warn("[History] 答复改写失败，本次不落历史: session={} err={}", sessionId, e.getMessage());
            return true;
        }
    }

    private void write(String userId, String sessionId, List<StoredMessage> messages) {
        // 会话已被删除：这一轮的流可能还在跑（删除不打断生成），跑完不能把它写回来。
        // 没有这道闸，用户删掉的会话会在几十秒后带着新标题"还魂"，删除语义等于失效
        if (isDeleted(userId, sessionId)) {
            log.info("[History] 会话已删除，丢弃本轮落盘: session={}", sessionId);
            return;
        }
        try {
            List<String> payloads = messages.stream().map(this::serialize).toList();
            String histKey = histKey(userId, sessionId);
            String metaKey = metaKey(userId, sessionId);
            String indexKey = indexKey(userId);
            String title = titleOf(messages.get(0).content());
            String createdAt = Instant.now().toString();
            long score = Instant.now().toEpochMilli();

            // 三条键的写入放进 MULTI/EXEC：中途失败留下的是残影而不是"没写过" ——
            // 比如 LPUSH 成功而 EXPIRE 落空，那是一条永不过期、又不在侧栏里的键；
            // ZADD 成功而 hist 失败，侧栏会有一行点开是空的会话
            redis.execute(new SessionCallback<Void>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public <K, V> Void execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> ops = (RedisOperations<String, String>) (RedisOperations) operations;
                    ops.multi();
                    // LPUSH 多值会逐个插到头，最终顺序与入参相反 —— 正好把最新的那批放在 LRANGE 的头部
                    ops.opsForList().leftPushAll(histKey, payloads);
                    ops.opsForList().trim(histKey, 0, MAX_MESSAGES - 1L);
                    ops.expire(histKey, TTL);
                    // 标题只在第一条消息时落一次：翻页截断、消息被裁掉都不会让它变样
                    ops.opsForHash().putIfAbsent(metaKey, "title", title);
                    ops.opsForHash().putIfAbsent(metaKey, "createdAt", createdAt);
                    ops.expire(metaKey, TTL);
                    ops.opsForZSet().add(indexKey, sessionId, score);
                    ops.expire(indexKey, TTL);
                    ops.exec();
                    return null;
                }
            });

            // 墓碑在写入期间才立起来的话（删除与落盘撞在同一瞬），上面那次 EXEC 会把它写回，
            // 这里补一次检查把残影清掉 —— 墓碑还在，说明删除仍然是用户的最终意图
            if (isDeleted(userId, sessionId)) {
                redis.delete(List.of(histKey, metaKey));
                redis.opsForZSet().remove(indexKey, sessionId);
                log.info("[History] 落盘期间会话被删除，已回滚本次写入: session={}", sessionId);
            }
        } catch (Exception e) {
            log.warn("[History] 会话记录失败，本轮不落历史: session={} err={}", sessionId, e.getMessage());
        }
    }

    private boolean isDeleted(String userId, String sessionId) {
        return Boolean.TRUE.equals(redis.hasKey(DELETED_PREFIX + userId + ":" + sessionId));
    }

    /** 侧栏列表，按最后活跃倒序。读取失败上抛（吞成空列表 = 告诉用户会话都没了） */
    public List<SessionSummary> listSessions(String userId, int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(indexKey(userId), 0, limit - 1L);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        List<SessionSummary> result = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String sessionId = tuple.getValue();
            if (sessionId == null) {
                continue;
            }
            Map<String, String> meta = redis.<String, String>opsForHash().entries(metaKey(userId, sessionId));
            if (meta.isEmpty()) {
                // 元数据先过期/被删、索引里还留着影子：跳过而不是编一行没有标题的记录
                continue;
            }
            Long count = redis.opsForList().size(histKey(userId, sessionId));
            result.add(new SessionSummary(
                    sessionId,
                    String.valueOf(meta.getOrDefault("title", "新对话")),
                    parseInstant(meta.get("createdAt")),
                    tuple.getScore() == null ? Instant.now() : Instant.ofEpochMilli(tuple.getScore().longValue()),
                    count == null ? 0 : count));
        }
        return result;
    }

    /** 单会话消息，按时间正序。读取失败上抛；单条解析失败跳过（一条坏记录不该让整段历史打不开） */
    public List<StoredMessage> loadMessages(String userId, String sessionId, int limit) {
        List<String> raw = redis.opsForList().range(histKey(userId, sessionId), 0, limit - 1L);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<StoredMessage> items = new ArrayList<>(raw.size());
        for (String json : raw) {
            try {
                items.add(objectMapper.readValue(json, StoredMessage.class));
            } catch (Exception e) {
                log.warn("[History] 跳过一条无法解析的历史记录: session={} err={}", sessionId, e.getMessage());
            }
        }
        // 存入是 LPUSH（新在头），读出来是倒序，反转回时间正序
        Collections.reverse(items);
        return items;
    }

    public boolean dismissApproval(String userId, String sessionId, String messageId) {
        String key = histKey(userId, sessionId);
        List<String> records = redis.opsForList().range(key, 0, MAX_MESSAGES - 1L);
        if (records == null) return false;
        for (String raw : records) {
            StoredMessage message = objectMapper.readValue(raw, StoredMessage.class);
            if (!message.id().equals(messageId) || message.response() == null
                    || !"assistant".equals(message.role())) continue;
            Map<String, Object> response = new java.util.LinkedHashMap<>(message.response());
            if ("CONFIRMED".equals(response.get("approvalStatus"))) return false;
            if (!(response.get("pendingActions") instanceof List<?> actions) || actions.isEmpty()) return false;
            response.remove("approvalToken");
            response.put("approvalStatus", "DISMISSED");
            response.put("stage", "DONE");
            response.put("reply", "已取消，本次没有执行任何操作。");
            StoredMessage updated = new StoredMessage(message.id(), message.role(),
                    "已取消，本次没有执行任何操作。", message.at(), response);
            var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                    "if redis.call('LINDEX',KEYS[1],0)~=ARGV[1] then return 0 end; "
                            + "redis.call('LSET',KEYS[1],0,ARGV[2]); return 1", Long.class);
            return Long.valueOf(1).equals(redis.execute(script, List.of(key), raw, serialize(updated)));
        }
        return false;
    }

    public boolean claimApproval(String userId, String sessionId, String token) {
        String key = histKey(userId, sessionId);
        String raw = redis.opsForList().index(key, 0);
        if (raw == null) return false;
        StoredMessage message = objectMapper.readValue(raw, StoredMessage.class);
        if (!"assistant".equals(message.role()) || message.response() == null
                || !token.equals(message.response().get("approvalToken"))) return false;
        Map<String, Object> response = new java.util.LinkedHashMap<>(message.response());
        response.remove("approvalToken");
        response.put("approvalStatus", "CONFIRMED");
        response.put("stage", "DONE");
        StoredMessage claimed = new StoredMessage(message.id(), message.role(), message.content(), message.at(), response);
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "if redis.call('LINDEX',KEYS[1],0)~=ARGV[1] then return 0 end; "
                        + "redis.call('LSET',KEYS[1],0,ARGV[2]); return 1", Long.class);
        return Long.valueOf(1).equals(redis.execute(script, List.of(key), raw, serialize(claimed)));
    }

    /**
     * 删除会话（历史 + 元数据 + 索引项），并立一块短命的「墓碑」。
     * <p>
     * 墓碑解决的是**删除撞上生成**：用户删掉会话时，服务端那一轮可能还在跑
     * （SSE 断开不打断生成），跑完会在 {@link #write} 里把它写回来 —— 实测过，
     * 删掉的会话约一分钟后带着新内容重新出现在侧栏。立了墓碑，那次写入就是空操作。
     * <p>
     * 墓碑 TTL 与历史一致（七天）：两种键同生共死，不存在"历史早没了、墓碑还挡着新会话"
     * 的错杀窗口 —— 会话号是客户端生成的时间戳，本来也不会被复用。
     * <p>
     * 返回是否真的删到了东西：**以「索引、历史、元数据任一被删掉」为准**，
     * 而不是只看索引。索引项先丢（如上次写入只成功了一半）而数据还在时，
     * 按索引判会回 404「不存在」，可数据明明刚被这次调用清掉 —— 状态码与事实相反。
     * 失败上抛。
     */
    public boolean delete(String userId, String sessionId) {
        redis.opsForValue().set(DELETED_PREFIX + userId + ":" + sessionId, "1", TTL);
        Long removedIndex = redis.opsForZSet().remove(indexKey(userId), sessionId);
        Long removedKeys = redis.delete(List.of(histKey(userId, sessionId), metaKey(userId, sessionId)));
        return (removedIndex != null && removedIndex > 0) || (removedKeys != null && removedKeys > 0);
    }

    /** 首条用户消息 → 侧栏标题：压平换行、截断。与 C 端聊天一致，标题就是用户说的第一句话 */
    static String titleOf(String message) {
        if (message == null) {
            return "新对话";
        }
        String flat = message.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return "新对话";
        }
        return flat.length() <= 24 ? flat : flat.substring(0, 24) + "…";
    }

    private String serialize(StoredMessage message) {
        return objectMapper.writeValueAsString(message);
    }

    /**
     * 领域对象 → 通用 Map。用 convertValue 而不是先序列化再反序列化：少一次字符串往返。
     * <p>
     * 转换失败**不向上抛**：它发生在写入的 try 之外，抛出去会连正文一起丢掉 ——
     * 而正文才是历史的主体，引用卡片、工具轨迹只是附属。附属转换不了就存个 null，
     * 那句话照常存下来。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(value, Map.class);
        } catch (Exception e) {
            log.warn("[History] 助手响应无法转为 Map，本轮的引用/轨迹将不落历史: err={}", e.getMessage());
            return null;
        }
    }

    private static Instant parseInstant(Object value) {
        if (value == null) {
            return Instant.now();
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private String histKey(String userId, String sessionId) {
        return HIST_PREFIX + userId + ":" + sessionId;
    }

    private String metaKey(String userId, String sessionId) {
        return META_PREFIX + userId + ":" + sessionId;
    }

    private String indexKey(String userId) {
        return INDEX_PREFIX + userId;
    }
}
