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
    private static final String APPROVAL_PREFIX = "chat:approval:";
    private static final Duration APPROVAL_LEASE = Duration.ofSeconds(30);

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

    /**
     * 一轮对话的稳定身份。ID 在服务端生成，实时响应、Redis 历史与反馈记录共用这组值。
     * 重新生成只改写答复正文，三项身份保持不变。
     */
    public record TurnIdentity(String turnId, String userMessageId, String assistantMessageId) {
        public TurnIdentity {
            if (turnId == null || turnId.isBlank()
                    || userMessageId == null || userMessageId.isBlank()
                    || assistantMessageId == null || assistantMessageId.isBlank()) {
                throw new IllegalArgumentException("对话身份不能为空");
            }
        }
    }

    /** 侧栏一行 */
    public record SessionSummary(String sessionId, String title, Instant createdAt, Instant updatedAt, long messageCount) {
    }

    public record ApprovalBatch(String token, StoredMessage original, String status, long acceptedAt,
                                String ownerNonce, long leaseUntil, Map<String, Object> result, long recoveryUntil) {
        public ApprovalBatch(String token, StoredMessage original, String status, long acceptedAt,
                             String ownerNonce, long leaseUntil, Map<String, Object> result) {
            this(token, original, status, acceptedAt, ownerNonce, leaseUntil, result, 0);
        }
        public boolean accepted() { return acceptedAt > 0; }
        public boolean cancelled() { return "CANCELLED".equals(status) || "DISMISSED".equals(status); }
    }

    public record ApprovalLease(ApprovalBatch batch, String ownerNonce, boolean writable) {
    }

    /** 仅表示授权存储不可读，不能用来吞掉签名或业务异常。 */
    public static final class ApprovalRecordUnreadableException extends IllegalStateException {
        public ApprovalRecordUnreadableException(Throwable cause) { super("原审批授权记录无法读取，只能查询权威回执", cause); }
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final java.time.Clock approvalClock;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatHistoryStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(redis, objectMapper, java.time.Clock.systemUTC());
    }

    ChatHistoryStore(StringRedisTemplate redis, ObjectMapper objectMapper, java.time.Clock approvalClock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.approvalClock = approvalClock;
    }

    /** 每一轮的身份只能由服务端产生，客户端生成的临时 key 不可用于反馈或授权。 */
    public static TurnIdentity newTurnIdentity() {
        String token = UUID.randomUUID().toString();
        return new TurnIdentity("turn-" + token, "u-" + token, "a-" + token);
    }

    /**
     * 找到当前会话最后一轮的稳定身份，供重新生成复用。
     * 旧历史没有身份字段时以已有消息 ID 兜底，保证升级前的数据仍可重新生成。
     */
    public TurnIdentity latestTurnIdentity(String userId, String sessionId) {
        List<StoredMessage> messages = loadMessages(userId, sessionId, MAX_MESSAGES);
        for (int i = messages.size() - 1; i >= 0; i--) {
            StoredMessage assistant = messages.get(i);
            if (!"assistant".equals(assistant.role())) {
                continue;
            }
            StoredMessage user = i > 0 ? messages.get(i - 1) : null;
            if (user == null || !"user".equals(user.role())) {
                continue;
            }
            Map<String, Object> response = assistant.response();
            String turnId = value(response, "turnId");
            String userMessageId = value(response, "userMessageId");
            String assistantMessageId = value(response, "assistantMessageId");
            return new TurnIdentity(
                    blankTo("turn-" + assistant.id(), turnId),
                    blankTo(user.id(), userMessageId),
                    blankTo(assistant.id(), assistantMessageId));
        }
        return null;
    }

    /**
     * 记一轮对话：用户消息 + 助手消息。
     * <p>
     * 一次调用写两条而不是两次调用：两条消息共用一次裁剪、一次续期、一次索引更新，
     * 中途失败也不会留下「有问无答」的半轮孤本。
     */
    public void recordTurn(String userId, String sessionId, String userMessage,
                           String assistantReply, Object assistantPayload) {
        Map<String, Object> payload = toMap(assistantPayload);
        TurnIdentity identity = identityFrom(payload);
        recordTurn(userId, sessionId, identity == null ? newTurnIdentity() : identity,
                userMessage, assistantReply, assistantPayload);
    }

    public void recordTurn(String userId, String sessionId, TurnIdentity identity,
                           String userMessage, String assistantReply, Object assistantPayload) {
        Instant now = Instant.now();
        List<StoredMessage> messages = List.of(
                new StoredMessage(identity.userMessageId(), "user", userMessage, now, null),
                new StoredMessage(identity.assistantMessageId(), "assistant",
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
        TurnIdentity identity = identityFrom(toMap(assistantPayload));
        if (identity == null) {
            identity = latestTurnIdentity(userId, sessionId);
        }
        recordAnswer(userId, sessionId, identity == null ? newTurnIdentity() : identity,
                assistantReply, assistantPayload);
    }

    public void recordAnswer(String userId, String sessionId, TurnIdentity identity,
                             String assistantReply, Object assistantPayload) {
        StoredMessage answer = new StoredMessage(identity.assistantMessageId(), "assistant",
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

    /** 用户只能看到自己命名空间下真实存在的会话；空历史不再伪装成越权会话。 */
    public boolean hasSession(String userId, String sessionId) {
        if (isDeleted(userId, sessionId)) {
            return false;
        }
        return Boolean.TRUE.equals(redis.hasKey(histKey(userId, sessionId)))
                || Boolean.TRUE.equals(redis.hasKey(metaKey(userId, sessionId)))
                || redis.opsForZSet().score(indexKey(userId), sessionId) != null;
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
            String token = value(response, "approvalToken");
            if (token == null) return false;
            String batchKey = approvalKey(userId, sessionId, token);
            String ledger = readApprovalRecord(batchKey);
            ApprovalBatch old = ledger == null ? new ApprovalBatch(token, message, "PENDING", 0, null, 0, null)
                    : decodeApprovalRecord(ledger);
            if (!token.equals(old.token()) || "CONFIRMED".equals(old.status())) return false;
            ApprovalBatch cancelled = new ApprovalBatch(token, old.original(), "CANCELLED", old.acceptedAt(), null, 0, old.result(), old.recoveryUntil());
            response.put("approvalStatus", "DISMISSED");
            response.put("stage", "DONE");
            String reply = old.accepted() ? "已停止后续操作；已开始操作的结果可查询原回执。" : "已取消，本次没有执行任何操作。";
            response.put("reply", reply);
            StoredMessage updated = new StoredMessage(message.id(), message.role(),
                    reply, message.at(), response);
            var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                    "local ledger=redis.call('HGET',KEYS[2],'record'); if (ledger or '')~=ARGV[3] then return 0 end; "
                            + "local rows=redis.call('LRANGE',KEYS[1],0,199); for i,row in ipairs(rows) do "
                            + "if row==ARGV[1] then redis.call('LSET',KEYS[1],i-1,ARGV[2]); "
                            + "redis.call('HSET',KEYS[2],'record',ARGV[4]); redis.call('PEXPIRE',KEYS[2],ARGV[5]); return 1 end end; return 0", Long.class);
            return Long.valueOf(1).equals(redis.execute(script, List.of(key, batchKey), raw, serialize(updated),
                    ledger == null ? "" : ledger, objectMapper.writeValueAsString(cancelled), String.valueOf(TTL.toMillis())));
        }
        return false;
    }

    boolean claimApproval(String userId, String sessionId, String token) {
        return beginApproval(userId, sessionId, token, true, true).writable();
    }

    /** 只有首次接受才要求最新待确认消息；已接受批次恢复依靠原 token 账本，不随新消息失效。 */
    ApprovalLease beginApproval(String userId, String sessionId, String token, boolean initial, boolean execute) {
        return beginApproval(userId, sessionId, token, initial, execute, 0, Long.MAX_VALUE / 1000 - 1, true);
    }

    public ApprovalLease beginApproval(String userId, String sessionId, String token, boolean initial, boolean execute,
                                       long recoverySeconds, long signatureExpiresAtSeconds, boolean signatureUnexpired) {
        String key = approvalKey(userId, sessionId, token);
        String existing = readApprovalRecord(key);
        ApprovalBatch batch = existing == null ? null : decodeApprovalRecord(existing);
        if (batch != null && !token.equals(batch.token())) throw new IllegalStateException("审批账本身份不匹配");
        long now = approvalClock.millis();
        long signatureUntil = Math.addExact(Math.multiplyExact(signatureExpiresAtSeconds, 1000), 999);
        if (recoverySeconds < 0 || recoverySeconds > 86400) throw new IllegalStateException("审批恢复期限无效");
        if (batch != null && batch.accepted()) {
            long expectedUntil = recoverySeconds == 0 ? signatureUntil : Math.addExact(batch.acceptedAt(), recoverySeconds * 1000);
            if (batch.acceptedAt() > signatureUntil || (batch.recoveryUntil() != expectedUntil
                    && !(recoverySeconds == 0 && batch.recoveryUntil() == 0))) {
                throw new ApprovalRecordUnreadableException(new IllegalArgumentException("审批接受时间或恢复截止时间不匹配原签名"));
            }
            if (execute && now >= expectedUntil) return new ApprovalLease(batch, null, false);
        } else if (execute && (!signatureUnexpired || now > signatureUntil)) return new ApprovalLease(batch, null, false);
        if (!execute) {
            try {
                if (batch != null && isDeleted(userId, sessionId)) {
                    batch = new ApprovalBatch(token, batch.original(), "CANCELLED", batch.acceptedAt(), null, 0,
                            batch.result(), batch.recoveryUntil());
                }
                return new ApprovalLease(batch == null ? historicalApproval(userId, sessionId, token) : batch, null, false);
            }
            catch (org.springframework.dao.DataAccessException unavailable) { throw new ApprovalRecordUnreadableException(unavailable); }
        }
        if (isDeleted(userId, sessionId)) throw new IllegalStateException("会话已删除，只能查询原操作回执");
        String expectedHead = "";
        if (batch == null || (!batch.accepted() && "PENDING".equals(batch.status()))) {
            if (!initial) throw new IllegalStateException("原授权接受记录不存在，只能查询执行结果");
            expectedHead = redis.opsForList().index(histKey(userId, sessionId), 0);
            if (expectedHead == null) return new ApprovalLease(null, null, false);
            StoredMessage head = objectMapper.readValue(expectedHead, StoredMessage.class);
            if (head.id() == null || head.id().isBlank() || head.at() == null || !"assistant".equals(head.role()) || head.response() == null
                    || !token.equals(head.response().get("approvalToken"))
                    || !(head.response().get("pendingActions") instanceof List<?> actions) || actions.isEmpty()
                    || (batch != null && !batch.original().id().equals(head.id()))
                    || (head.response().get("approvalStatus") != null && !"PENDING".equals(head.response().get("approvalStatus")))) {
                return new ApprovalLease(null, null, false);
            }
            batch = new ApprovalBatch(token, head, "PENDING", 0, null, 0, null);
        } else if ((initial && batch.accepted()) || batch.cancelled() || "CONFIRMED".equals(batch.status()) || batch.leaseUntil() > now) {
            return new ApprovalLease(batch, null, false);
        }
        String owner = UUID.randomUUID().toString();
        ApprovalBatch acquired = new ApprovalBatch(token, batch.original(), "IN_FLIGHT",
                batch.acceptedAt() == 0 ? now : batch.acceptedAt(), owner, now + APPROVAL_LEASE.toMillis(), batch.result(),
                batch.accepted() ? batch.recoveryUntil() : recoverySeconds == 0 ? signatureUntil : now + recoverySeconds * 1000);
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "local current=redis.call('HGET',KEYS[1],'record'); "
                        + "if ARGV[1]=='' then if current then return 0 end "
                        + "else if current~=ARGV[1] then return 0 end end; "
                        + "if ARGV[2]~='' and redis.call('LINDEX',KEYS[2],0)~=ARGV[2] then return 0 end; "
                        + "if redis.call('EXISTS',KEYS[3])==1 then return 0 end; "
                        + "local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000); if now>tonumber(ARGV[5]) then return 0 end; "
                        + "redis.call('HSET',KEYS[1],'record',ARGV[3]); redis.call('PEXPIRE',KEYS[1],ARGV[4]); return 1", Long.class);
        Long accepted = redis.execute(script, List.of(key, histKey(userId, sessionId), DELETED_PREFIX + userId + ":" + sessionId),
                existing == null ? "" : existing, expectedHead, objectMapper.writeValueAsString(acquired), String.valueOf(TTL.toMillis()),
                String.valueOf(batch.accepted() ? acquired.recoveryUntil() - 1 : signatureUntil));
        if (!Long.valueOf(1).equals(accepted)) return new ApprovalLease(batch, null, false);
        updateApprovalMessage(userId, sessionId, acquired, null);
        return new ApprovalLease(acquired, owner, true);
    }

    public ApprovalBatch historicalApproval(String userId, String sessionId, String token) {
        for (StoredMessage message : loadMessages(userId, sessionId, MAX_MESSAGES)) {
            if ("assistant".equals(message.role()) && message.response() != null && token.equals(message.response().get("approvalToken"))) {
                return new ApprovalBatch(token, message, value(message.response(), "approvalStatus"), 0, null, 0, null);
            }
        }
        return null;
    }

    /** 续租也校验 owner；租约被接管或用户取消后，旧执行者不得继续后续写操作。 */
    public boolean renewApproval(String userId, String sessionId, String token, String owner) {
        String key = approvalKey(userId, sessionId, token);
        String raw = readApprovalRecord(key);
        if (raw == null || isDeleted(userId, sessionId)) return false;
        ApprovalBatch old = decodeApprovalRecord(raw);
        if (!token.equals(old.token()) || !owner.equals(old.ownerNonce()) || old.cancelled()
                || old.leaseUntil() <= approvalClock.millis()
                || (old.recoveryUntil() > 0 && old.recoveryUntil() <= approvalClock.millis())) return false;
        ApprovalBatch next = new ApprovalBatch(token, old.original(), "IN_FLIGHT", old.acceptedAt(), owner,
                approvalClock.millis() + APPROVAL_LEASE.toMillis(), old.result(), old.recoveryUntil());
        return compareAndSetOwnedApproval(userId, sessionId, key, raw, next, old);
    }

    public boolean finishApproval(String userId, String sessionId, String token, String owner,
                                  String status, Object result) {
        String key = approvalKey(userId, sessionId, token);
        String raw = readApprovalRecord(key);
        if (raw == null) return false;
        ApprovalBatch old = decodeApprovalRecord(raw);
        if (!token.equals(old.token()) || !owner.equals(old.ownerNonce()) || old.cancelled()
                || old.leaseUntil() <= approvalClock.millis()) return false;
        Map<String, Object> payload = toMap(result);
        if (payload == null) throw new IllegalStateException("审批结果无法保存，保留未知状态");
        ApprovalBatch next = new ApprovalBatch(token, old.original(), status, old.acceptedAt(), null, 0, payload, old.recoveryUntil());
        if (!compareAndSetOwnedApproval(userId, sessionId, key, raw, next, old)) return false;
        updateApprovalMessage(userId, sessionId, next, payload);
        return true;
    }

    /** 完成账本、墓碑与单次发布在同一Lua内核对，取消或失去所有权的请求不能复活新确认卡。 */
    public boolean publishNextApproval(String userId, String sessionId, String token, String userMessage, Object response) {
        String key = approvalKey(userId, sessionId, token);
        String raw = readApprovalRecord(key);
        if (raw == null) return false;
        ApprovalBatch batch = decodeApprovalRecord(raw);
        if (!"CONFIRMED".equals(batch.status())) return false;
        Map<String, Object> payload = toMap(response);
        TurnIdentity identity = identityFrom(payload);
        if (identity == null || payload == null || !"PENDING".equals(payload.get("approvalStatus"))) return false;
        Instant now = Instant.now();
        StoredMessage user = new StoredMessage(identity.userMessageId(), "user", userMessage, now, null);
        StoredMessage assistant = new StoredMessage(identity.assistantMessageId(), "assistant", value(payload, "reply"), now, payload);
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "if redis.call('HGET',KEYS[1],'record')~=ARGV[1] or redis.call('EXISTS',KEYS[2])==1 "
                        + "or redis.call('HEXISTS',KEYS[1],'nextPublished')==1 then return 0 end; "
                        + "redis.call('HSET',KEYS[1],'nextPublished','1'); "
                        + "redis.call('LPUSH',KEYS[3],ARGV[2],ARGV[3]); redis.call('LTRIM',KEYS[3],0,199); "
                        + "redis.call('HSETNX',KEYS[4],'title',ARGV[4]); redis.call('HSETNX',KEYS[4],'createdAt',ARGV[5]); "
                        + "redis.call('ZADD',KEYS[5],ARGV[6],ARGV[7]); "
                        + "for i=1,5 do if i~=2 then redis.call('PEXPIRE',KEYS[i],ARGV[8]) end end; return 1", Long.class);
        return Long.valueOf(1).equals(redis.execute(script, List.of(key, DELETED_PREFIX + userId + ":" + sessionId,
                histKey(userId, sessionId), metaKey(userId, sessionId), indexKey(userId)), raw, serialize(user),
                serialize(assistant), titleOf(userMessage), now.toString(), String.valueOf(now.toEpochMilli()), sessionId,
                String.valueOf(TTL.toMillis())));
    }

    private boolean compareAndSetApproval(String key, String expected, ApprovalBatch next) {
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "if redis.call('HGET',KEYS[1],'record')~=ARGV[1] then return 0 end; "
                        + "redis.call('HSET',KEYS[1],'record',ARGV[2]); redis.call('PEXPIRE',KEYS[1],ARGV[3]); return 1", Long.class);
        return Long.valueOf(1).equals(redis.execute(script, List.of(key), expected,
                objectMapper.writeValueAsString(next), String.valueOf(TTL.toMillis())));
    }

    private boolean compareAndSetOwnedApproval(String userId, String sessionId, String key, String expected,
                                               ApprovalBatch next, ApprovalBatch old) {
        long deadline = old.recoveryUntil() > 0 ? Math.min(old.leaseUntil(), old.recoveryUntil()) : old.leaseUntil();
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "if redis.call('HGET',KEYS[1],'record')~=ARGV[1] then return 0 end; "
                        + "if redis.call('EXISTS',KEYS[2])==1 then return 0 end; "
                        + "local t=redis.call('TIME'); if t[1]*1000+math.floor(t[2]/1000)>=tonumber(ARGV[4]) then return 0 end; "
                        + "redis.call('HSET',KEYS[1],'record',ARGV[2]); redis.call('PEXPIRE',KEYS[1],ARGV[3]); return 1", Long.class);
        return Long.valueOf(1).equals(redis.execute(script, List.of(key, DELETED_PREFIX + userId + ":" + sessionId), expected,
                objectMapper.writeValueAsString(next), String.valueOf(TTL.toMillis()), String.valueOf(deadline)));
    }

    private void updateApprovalMessage(String userId, String sessionId, ApprovalBatch batch, Map<String, Object> result) {
        if (batch.original() == null || isDeleted(userId, sessionId)) return;
        Map<String, Object> response = new java.util.LinkedHashMap<>(batch.original().response());
        if (result != null) response.putAll(result);
        response.put("approvalToken", batch.token());
        response.put("approvalStatus", batch.status());
        response.put("approvalRecoveryUntil", batch.recoveryUntil() == 0 ? null : batch.recoveryUntil());
        if (batch.cancelled() || "CONFIRMED".equals(batch.status()) || batch.leaseUntil() > approvalClock.millis()) {
            response.put("approvalRetryAllowed", false);
        }
        response.put("stage", "CONFIRMED".equals(batch.status()) ? "DONE" : "WAITING_USER");
        response.put("pendingActions", batch.original().response().get("pendingActions"));
        response.put("pendingActionDetails", batch.original().response().get("pendingActionDetails"));
        response.put("assistantMessageId", batch.original().id());
        response.put("turnId", batch.original().response().get("turnId"));
        response.put("userMessageId", batch.original().response().get("userMessageId"));
        String content = value(response, "reply");
        StoredMessage updated = new StoredMessage(batch.original().id(), "assistant",
                content == null ? batch.original().content() : content, batch.original().at(), response);
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "if redis.call('HGET',KEYS[2],'record')~=ARGV[3] then return 0 end; "
                        + "local rows=redis.call('LRANGE',KEYS[1],0,199); "
                        + "for i,raw in ipairs(rows) do local ok,row=pcall(cjson.decode,raw); "
                        + "if ok and row.id==ARGV[1] and row.response and row.response.approvalToken==ARGV[4] "
                        + "then redis.call('LSET',KEYS[1],i-1,ARGV[2]); return 1 end end; return 0", Long.class);
        redis.execute(script, List.of(histKey(userId, sessionId), approvalKey(userId, sessionId, batch.token())),
                batch.original().id(), serialize(updated), objectMapper.writeValueAsString(batch), batch.token());
    }

    public void observeApproval(String userId, String sessionId, String token, String status, Object response) {
        String key = approvalKey(userId, sessionId, token);
        String raw = readApprovalRecord(key);
        if (raw == null) return;
        ApprovalBatch old = decodeApprovalRecord(raw);
        if (!token.equals(old.token()) || old.leaseUntil() > approvalClock.millis() || "CONFIRMED".equals(old.status())) return;
        Map<String, Object> payload = toMap(response);
        if (payload == null) throw new IllegalStateException("审批回执查询结果无法保存");
        ApprovalBatch next = new ApprovalBatch(token, old.original(), old.cancelled() ? old.status() : status,
                old.acceptedAt(), null, 0, payload, old.recoveryUntil());
        if (compareAndSetApproval(key, raw, next)) updateApprovalMessage(userId, sessionId, next, payload);
    }

    private static String approvalKey(String userId, String sessionId, String token) {
        if (token == null || token.isBlank() || token.length() > 8192) throw new IllegalArgumentException("原审批令牌不能为空或超长");
        try {
            String fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return APPROVAL_PREFIX + userId + ":" + sessionId + ":" + fingerprint;
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("无法计算审批指纹", impossible); }
    }

    private String readApprovalRecord(String key) {
        try {
            Object value = redis.opsForHash().get(key, "record");
            return value == null ? null : value.toString();
        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw new ApprovalRecordUnreadableException(unavailable);
        }
    }

    private ApprovalBatch decodeApprovalRecord(String json) {
        try {
            ApprovalBatch batch = objectMapper.readValue(json, ApprovalBatch.class);
            if (batch == null || batch.token() == null || batch.token().isBlank() || batch.status() == null
                    || !java.util.Set.of("PENDING", "IN_FLIGHT", "UNKNOWN", "REJECTED", "CONFIRMED", "CANCELLED", "DISMISSED").contains(batch.status())
                    || batch.original() == null || batch.original().id() == null || batch.original().id().isBlank()
                    || !"assistant".equals(batch.original().role()) || batch.original().at() == null
                    || batch.original().response() == null || !batch.token().equals(batch.original().response().get("approvalToken"))
                    || !(batch.original().response().get("pendingActions") instanceof List<?> actions) || actions.isEmpty()
                    || batch.acceptedAt() < 0 || batch.leaseUntil() < 0 || batch.recoveryUntil() < 0
                    || (batch.acceptedAt() == 0 && !"PENDING".equals(batch.status()) && !batch.cancelled())
                    || ("IN_FLIGHT".equals(batch.status()) && (batch.ownerNonce() == null || batch.ownerNonce().isBlank() || batch.leaseUntil() == 0))) {
                throw new IllegalArgumentException("授权记录字段缺失或原消息身份不匹配");
            }
            return batch;
        } catch (RuntimeException corrupted) {
            throw new ApprovalRecordUnreadableException(corrupted);
        }
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

    private static String value(Map<String, Object> response, String name) {
        if (response == null) {
            return null;
        }
        Object value = response.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private static TurnIdentity identityFrom(Map<String, Object> response) {
        String turnId = value(response, "turnId");
        String userMessageId = value(response, "userMessageId");
        String assistantMessageId = value(response, "assistantMessageId");
        if (turnId == null || userMessageId == null || assistantMessageId == null) {
            return null;
        }
        try {
            return new TurnIdentity(turnId, userMessageId, assistantMessageId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String blankTo(String fallback, String value) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
