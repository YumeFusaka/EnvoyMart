package yumefusaka.envoymart.aiservice.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import java.util.Comparator;

/** 单条助手回答的反馈记录。反馈与聊天历史分开存储，避免用户反馈改变历史消息。 */
@Slf4j
@Component
public class BadCaseStore {
    private static final String PREFIX = "chat:badcase:";
    private static final String ID_INDEX_PREFIX = "chat:badcase:id:";
    private static final String INDEX = "chat:badcases";
    private static final String SESSION_INDEX_PREFIX = "chat:badcases:session:";
    private static final String FIXTURE_PREFIX = "eval:badcase-fixture:";
    private static final String AUDIT_PREFIX = "chat:badcase:audit:";
    private static final Duration TTL = Duration.ofDays(7);
    private static final int MAX_COMMENT = 1000;

    public enum Status { ACTIVE, REVIEWED, IN_TEST_SET, REJECTED, REVOKED }
    public enum Reason { FACT_ERROR, NO_ANSWER, IRRELEVANT_EVIDENCE, TOOL_ERROR, HARD_TO_READ, OTHER }

    public record FeedbackRequest(List<Reason> reasonCodes, List<String> selectedMessageIds, String comment) {}
    public record AuditEvent(String action, String badCaseId, String operator, Instant at, String testCaseId) {}
    /** 追加集样本契约。它独立于基础评测集，不参与基础指标分母。 */
    public record Fixture(String caseId, String sourceBadCaseId, String question, String answer,
                          List<Reason> reasonCodes, List<String> selectedMessageIds,
                          Map<String, Object> responseSnapshot, String addedBy, Instant addedAt,
                          String evalKind, boolean expectRefuse, List<String> mustMention,
                          List<String> expectedTools, String annotation) {}
    public record BadCase(String badCaseId, String userId, String sessionId, String assistantMessageId,
                          String userMessageId, List<String> selectedMessageIds, List<Reason> reasonCodes,
                          String comment, String question, String answer, Map<String, Object> responseSnapshot,
                          Instant createdAt, Instant updatedAt, Status status, String reviewedBy,
                          Instant reviewedAt, String testCaseId) {}

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public BadCaseStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public BadCase upsert(String userId, String sessionId, String assistantMessageId, FeedbackRequest request) {
        validate(request);
        List<ChatHistoryStore.StoredMessage> messages = ChatHistoryStoreMessages.load(redis, mapper, userId, sessionId);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        int index = indexOf(messages, assistantMessageId);
        if (index < 0 || !"assistant".equals(messages.get(index).role())) {
            throw new IllegalArgumentException("助手消息不存在");
        }
        ChatHistoryStore.StoredMessage assistant = messages.get(index);
        ChatHistoryStore.StoredMessage question = index > 0 ? messages.get(index - 1) : null;
        if (question == null || !"user".equals(question.role())) {
            throw new IllegalArgumentException("对应用户问题不存在");
        }
        Set<String> allowed = messages.stream().map(ChatHistoryStore.StoredMessage::id)
                .collect(java.util.stream.Collectors.toSet());
        List<String> selected = request.selectedMessageIds() == null ? List.of() : request.selectedMessageIds();
        if (selected.size() > messages.size()) {
            throw new IllegalArgumentException("关联消息数量超过当前会话消息数");
        }
        if (selected.stream().anyMatch(id -> id == null || id.isBlank() || !allowed.contains(id))) {
            throw new IllegalArgumentException("关联消息不属于当前会话");
        }
        if (selected.stream().distinct().count() != selected.size()) {
            throw new IllegalArgumentException("关联消息不能重复选择");
        }
        String key = key(userId, sessionId, assistantMessageId);
        BadCase old = read(key);
        Instant now = Instant.now();
        BadCase value = new BadCase(old == null ? UUID.randomUUID().toString() : old.badCaseId(), userId, sessionId,
                assistantMessageId, question.id(), List.copyOf(selected), List.copyOf(request.reasonCodes()),
                sanitize(request.comment()), question.content(), assistant.content(),
                assistant.response() == null ? Map.of() : sanitizeMap(assistant.response()),
                old == null ? now : old.createdAt(), now,
                old == null || old.status() == Status.REVOKED ? Status.ACTIVE : old.status(),
                old == null || old.status() == Status.REVOKED ? null : old.reviewedBy(),
                old == null || old.status() == Status.REVOKED ? null : old.reviewedAt(),
                old == null || old.status() == Status.REVOKED ? null : old.testCaseId());
        write(key, value);
        redis.opsForValue().set(ID_INDEX_PREFIX + value.badCaseId(), key, TTL);
        redis.opsForZSet().add(INDEX, key, now.toEpochMilli());
        redis.opsForZSet().add(SESSION_INDEX_PREFIX + userId + ":" + sessionId, key, now.toEpochMilli());
        redis.expire(INDEX, TTL);
        redis.expire(SESSION_INDEX_PREFIX + userId + ":" + sessionId, TTL);
        return value;
    }

    public BadCase get(String userId, String sessionId, String assistantMessageId) {
        return read(key(userId, sessionId, assistantMessageId));
    }

    public boolean revoke(String userId, String sessionId, String assistantMessageId) {
        String key = key(userId, sessionId, assistantMessageId);
        BadCase old = read(key);
        if (old == null) return false;
        if (old.status() == Status.REVIEWED || old.status() == Status.IN_TEST_SET) {
            throw new IllegalStateException("已审核反馈不能撤销");
        }
        if (old.status() == Status.REVOKED) return true;
        BadCase value = new BadCase(old.badCaseId(), old.userId(), old.sessionId(), old.assistantMessageId(), old.userMessageId(),
                old.selectedMessageIds(), old.reasonCodes(), old.comment(), old.question(), old.answer(), old.responseSnapshot(),
                old.createdAt(), Instant.now(), Status.REVOKED, old.reviewedBy(), old.reviewedAt(), old.testCaseId());
        write(key, value);
        return true;
    }

    public List<BadCase> list(int limit) {
        return listPage(0, limit);
    }

    public List<BadCase> listPage(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 1000));
        long start = (long) safePage * safeSize;
        Set<String> keys = redis.opsForZSet().reverseRange(INDEX, start, start + safeSize - 1L);
        if (keys == null) return List.of();
        List<BadCase> result = new ArrayList<>();
        for (String key : keys) { BadCase item = read(key); if (item != null) result.add(item); }
        return result;
    }

    public List<BadCase> listForSession(String userId, String sessionId) {
        Set<String> keys = redis.opsForZSet().reverseRange(SESSION_INDEX_PREFIX + userId + ":" + sessionId, 0, 999L);
        if (keys == null) return List.of();
        List<BadCase> result = new ArrayList<>();
        for (String key : keys) { BadCase item = read(key); if (item != null && userId.equals(item.userId())) result.add(item); }
        return result;
    }

    public BadCase review(String id, Status status, String reviewer) {
        if (status != Status.REVIEWED && status != Status.REJECTED) throw new IllegalArgumentException("审核状态不合法");
        BadCase old = findById(id);
        BadCase value = new BadCase(old.badCaseId(), old.userId(), old.sessionId(), old.assistantMessageId(), old.userMessageId(),
                old.selectedMessageIds(), old.reasonCodes(), old.comment(), old.question(), old.answer(), old.responseSnapshot(),
                old.createdAt(), Instant.now(), status, reviewer, Instant.now(), old.testCaseId());
        write(key(old.userId(), old.sessionId(), old.assistantMessageId()), value);
        audit("REVIEW_" + status, value, reviewer);
        return value;
    }

    public BadCase addTestCase(String id, String reviewer, FixtureAnnotation annotation) {
        BadCase old = findById(id);
        if (old.status() != Status.REVIEWED && old.status() != Status.IN_TEST_SET) throw new IllegalArgumentException("请先审核通过");
        if (annotation == null || annotation.evalKind() == null || annotation.evalKind().isBlank()) {
            throw new IllegalArgumentException("追加测试集必须填写评测类型");
        }
        String testCaseId = old.testCaseId() == null ? "bad-" + old.badCaseId() : old.testCaseId();
        BadCase value = new BadCase(old.badCaseId(), old.userId(), old.sessionId(), old.assistantMessageId(), old.userMessageId(),
                old.selectedMessageIds(), old.reasonCodes(), old.comment(), old.question(), old.answer(), old.responseSnapshot(),
                old.createdAt(), Instant.now(), Status.IN_TEST_SET, reviewer, Instant.now(), testCaseId);
        write(key(old.userId(), old.sessionId(), old.assistantMessageId()), value);
        Fixture fixture = new Fixture(testCaseId, old.badCaseId(), old.question(), old.answer(), old.reasonCodes(), old.selectedMessageIds(), old.responseSnapshot(), reviewer, Instant.now(),
                annotation.evalKind().trim(), annotation.expectRefuse(), safeList(annotation.mustMention()), safeList(annotation.expectedTools()), sanitize(annotation.annotation()));
        redis.opsForValue().set(FIXTURE_PREFIX + testCaseId, writeJson(fixture), TTL);
        audit("ADD_TEST_CASE", value, reviewer);
        return value;
    }

    public record FixtureAnnotation(String evalKind, boolean expectRefuse, List<String> mustMention,
                                    List<String> expectedTools, String annotation) {}

    public List<AuditEvent> audit(String badCaseId) {
        List<String> raw = redis.opsForList().range(AUDIT_PREFIX + badCaseId, 0, 99);
        if (raw == null) return List.of();
        List<AuditEvent> result = new ArrayList<>();
        for (String item : raw) try { result.add(mapper.readValue(item, AuditEvent.class)); } catch (Exception ignored) { }
        return result;
    }

    public Fixture fixture(String caseId) {
        String raw = redis.opsForValue().get(FIXTURE_PREFIX + caseId);
        try { return raw == null ? null : mapper.readValue(raw, Fixture.class); } catch (Exception e) { return null; }
    }

    public List<Fixture> fixtures(int limit) {
        int safe = Math.max(1, Math.min(limit, 1000));
        Set<String> keys = redis.keys(FIXTURE_PREFIX + "*");
        if (keys == null) return List.of();
        return keys.stream().sorted(Comparator.reverseOrder()).limit(safe).map(this::readFixture).filter(java.util.Objects::nonNull).toList();
    }

    private Fixture readFixture(String key) {
        try { return mapper.readValue(redis.opsForValue().get(key), Fixture.class); } catch (Exception e) { return null; }
    }

    public BadCase adminView(BadCase item) {
        if (item == null) return null;
        return new BadCase(item.badCaseId(), maskUser(item.userId()), item.sessionId(), item.assistantMessageId(), item.userMessageId(),
                item.selectedMessageIds(), item.reasonCodes(), item.comment(), item.question(), item.answer(), item.responseSnapshot(),
                item.createdAt(), item.updatedAt(), item.status(), item.reviewedBy(), item.reviewedAt(), item.testCaseId());
    }

    public BadCase findForAdmin(String id) { return findById(id); }

    public boolean removeTestCase(String id, String operator) {
        BadCase old = findById(id);
        if (old.testCaseId() == null) return false;
        redis.delete(FIXTURE_PREFIX + old.testCaseId());
        BadCase value = new BadCase(old.badCaseId(), old.userId(), old.sessionId(), old.assistantMessageId(), old.userMessageId(),
                old.selectedMessageIds(), old.reasonCodes(), old.comment(), old.question(), old.answer(), old.responseSnapshot(),
                old.createdAt(), Instant.now(), Status.REVIEWED, operator, Instant.now(), null);
        write(key(old.userId(), old.sessionId(), old.assistantMessageId()), value);
        audit("REMOVE_TEST_CASE", value, operator);
        return true;
    }

    private BadCase findById(String id) {
        String key = redis.opsForValue().get(ID_INDEX_PREFIX + id);
        if (key != null) {
            BadCase value = read(key);
            if (value != null && value.badCaseId().equals(id)) return value;
        }
        throw new IllegalArgumentException("反馈不存在");
    }

    private void audit(String action, BadCase value, String operator) {
        AuditEvent event = new AuditEvent(action, value.badCaseId(), operator, Instant.now(), value.testCaseId());
        try { redis.opsForList().leftPush(AUDIT_PREFIX + value.badCaseId(), mapper.writeValueAsString(event)); redis.expire(AUDIT_PREFIX + value.badCaseId(), TTL); } catch (Exception e) { log.warn("[BadCase] 审计写入失败 id={} action={}", value.badCaseId(), action); }
    }

    private String writeJson(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("反馈追加集保存失败", e); } }
    private String maskUser(String userId) { if (userId == null || userId.length() < 3) return "***"; return userId.substring(0, 1) + "***" + userId.substring(userId.length() - 1); }

    private void validate(FeedbackRequest request) {
        if (request == null || request.reasonCodes() == null || request.reasonCodes().isEmpty() || request.reasonCodes().size() > 6) throw new IllegalArgumentException("请选择反馈原因");
        if (request.reasonCodes().stream().anyMatch(java.util.Objects::isNull)
                || request.reasonCodes().stream().distinct().count() != request.reasonCodes().size()) {
            throw new IllegalArgumentException("反馈原因不能重复或为空");
        }
        if (request.comment() != null && request.comment().length() > MAX_COMMENT) throw new IllegalArgumentException("说明不能超过1000字");
    }
    private String sanitize(String value) { return value == null ? null : value.replaceAll("[\\r\\n\\t]", " ").trim(); }
    private List<String> safeList(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(java.util.Objects::nonNull).map(String::trim).filter(v -> !v.isEmpty()).distinct().limit(20).toList();
    }
    private Map<String, Object> sanitizeMap(Map<String, Object> input) {
        Map<String, Object> output = new LinkedHashMap<>();
        input.forEach((k, v) -> { if (!k.equalsIgnoreCase("authorization") && !k.equalsIgnoreCase("cookie") && !k.toLowerCase().contains("token")) output.put(k, v); });
        return output;
    }
    private int indexOf(List<ChatHistoryStore.StoredMessage> messages, String id) { for (int i = 0; i < messages.size(); i++) if (id.equals(messages.get(i).id())) return i; return -1; }
    private String key(String userId, String sessionId, String messageId) { return PREFIX + userId + ":" + sessionId + ":" + messageId; }
    private BadCase read(String key) { try { String raw = redis.opsForValue().get(key); return raw == null ? null : mapper.readValue(raw, BadCase.class); } catch (Exception e) { log.warn("[BadCase] 读取失败 key={} err={}", key, e.getMessage()); return null; } }
    private void write(String key, BadCase value) { try { redis.opsForValue().set(key, mapper.writeValueAsString(value), TTL); } catch (Exception e) { throw new IllegalStateException("反馈保存失败", e); } }

    /** 用同一组 Redis 键读取历史，保证 Bad Case 校验不依赖控制器先读后信任。 */
    static final class ChatHistoryStoreMessages {
        static List<ChatHistoryStore.StoredMessage> load(StringRedisTemplate redis, ObjectMapper mapper, String userId, String sessionId) {
            String key = "chat:hist:" + userId + ":" + sessionId;
            List<String> raw = redis.opsForList().range(key, 0, 199);
            if (raw == null) return List.of();
            List<ChatHistoryStore.StoredMessage> result = new ArrayList<>();
            for (String item : raw) try { result.add(mapper.readValue(item, ChatHistoryStore.StoredMessage.class)); } catch (Exception ignored) { }
            java.util.Collections.reverse(result);
            return result;
        }
    }
}
