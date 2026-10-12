package yumefusaka.envoymart.aiservice.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 状态测试用原子键值替身；Lua 本身由浏览器/服务联验读取真实 Redis 兑约。 */
class ChatApprovalLeaseTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> values = new HashMap<>();
    private StringRedisTemplate redis;
    private ChatHistoryStore store;
    private String original;
    private ChatHistoryStore.StoredMessage lastUpdated;
    private long now = 1791687600000L;
    private Clock testClock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS);
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now); }
        };
        store = new ChatHistoryStore(redis, mapper, clock);
        testClock = clock;
        var message = new ChatHistoryStore.StoredMessage("a-original", "assistant", "原待确认", clock.instant(),
                Map.of("approvalToken", "signed-token", "approvalStatus", "PENDING", "pendingActions", List.of("cart_add")));
        original = mapper.writeValueAsString(message);
        when(redis.opsForList().index("chat:hist:u:s", 0)).thenReturn(original);
        when(redis.opsForList().range("chat:hist:u:s", 0, 199L)).thenReturn(List.of(original));
        when(redis.opsForHash().get(anyString(), eq("record"))).thenAnswer(call -> values.get(call.getArgument(0)));
        doAnswer(call -> {
            RedisScript<Long> script = call.getArgument(0);
            List<String> keys = call.getArgument(1);
            Object[] args = (Object[]) call.getRawArguments()[2];
            synchronized (values) {
                if (script.getScriptAsString().contains("nextPublished")) {
                    if (!args[0].equals(values.get(keys.getFirst())) || values.containsKey(keys.getFirst() + ":published")
                            || Boolean.TRUE.equals(redis.hasKey(keys.get(1)))) return 0L;
                    assertThat(script.getScriptAsString()).contains("HEXISTS", "EXISTS", "LPUSH", "LTRIM", "ZADD");
                    values.put(keys.getFirst() + ":published", "1");
                    lastUpdated = mapper.readValue((String) args[2], ChatHistoryStore.StoredMessage.class);
                    return 1L;
                }
                if (script.getScriptAsString().contains("local current=")) {
                    String expected = (String) args[0];
                    String found = values.get(keys.getFirst());
                    if (expected.isEmpty() ? found != null : !expected.equals(found)) return 0L;
                    if (!((String) args[1]).isEmpty() && !original.equals(args[1])) return 0L;
                    assertThat(script.getScriptAsString()).contains("LINDEX", "KEYS[3]", "PEXPIRE");
                    values.put(keys.getFirst(), (String) args[2]); return 1L;
                }
                if (script.getScriptAsString().startsWith("if redis.call('HGET',KEYS[1],'record')")) {
                    if (!args[0].equals(values.get(keys.getFirst()))) return 0L;
                    values.put(keys.getFirst(), (String) args[1]); return 1L;
                }
                if (script.getScriptAsString().contains("pcall(cjson.decode")) {
                    lastUpdated = mapper.readValue((String) args[1], ChatHistoryStore.StoredMessage.class);
                }
                return 1L;
            }
        }).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void 原令牌认领保留载荷并且并发第二次只能查询() {
        var first = store.beginApproval("u", "s", "signed-token", true, true);
        var second = store.beginApproval("u", "s", "signed-token", true, true);
        assertThat(first.writable()).isTrue();
        assertThat(first.batch().token()).isEqualTo("signed-token");
        assertThat(first.batch().status()).isEqualTo("IN_FLIGHT");
        assertThat(first.batch().original().id()).isEqualTo("a-original");
        assertThat(first.batch().leaseUntil()).isGreaterThan(now);
        assertThat(second.writable()).isFalse();
        assertThat(second.batch().ownerNonce()).isEqualTo(first.ownerNonce());
    }

    @Test
    void 租约到期后正常聊天不自动续写但显式恢复可接管且旧owner不能覆盖() {
        var first = store.beginApproval("u", "s", "signed-token", true, true);
        now += 16 * 60000L;
        assertThat(store.beginApproval("u", "s", "signed-token", true, true).writable()).isFalse();
        var recovery = store.beginApproval("u", "s", "signed-token", false, true);
        assertThat(recovery.writable()).isTrue();
        assertThat(recovery.ownerNonce()).isNotEqualTo(first.ownerNonce());
        assertThat(recovery.batch().acceptedAt()).isEqualTo(first.batch().acceptedAt());
        assertThat(store.finishApproval("u", "s", "signed-token", first.ownerNonce(), "CONFIRMED", Map.of("reply", "迟到结果"))).isFalse();
    }

    @Test
    void 未知结果不会消耗原token且新store可继续查询旧批次() {
        var first = store.beginApproval("u", "s", "signed-token", true, true);
        assertThat(store.finishApproval("u", "s", "signed-token", first.ownerNonce(), "UNKNOWN", Map.of("reply", "结果待确认"))).isTrue();
        ChatHistoryStore restarted = new ChatHistoryStore(redis, mapper);
        var queried = restarted.beginApproval("u", "s", "signed-token", false, false);
        assertThat(queried.batch().status()).isEqualTo("UNKNOWN");
        assertThat(queried.batch().token()).isEqualTo("signed-token");
        assertThat(queried.batch().result()).containsEntry("reply", "结果待确认");
        assertThat(queried.writable()).isFalse();
    }

    @Test
    void 完成批次只能重放查询不能再次获得写租约() {
        var first = store.beginApproval("u", "s", "signed-token", true, true);
        store.finishApproval("u", "s", "signed-token", first.ownerNonce(), "CONFIRMED", Map.of("reply", "权威回执"));
        assertThat(store.beginApproval("u", "s", "signed-token", false, true).writable()).isFalse();
        assertThat(store.beginApproval("u", "s", "signed-token", false, false).batch().status()).isEqualTo("CONFIRMED");
    }

    @Test
    void 取消后迟到查询回写历史不能重新开放继续确认() {
        var first = store.beginApproval("u", "s", "signed-token", true, true);
        store.finishApproval("u", "s", "signed-token", first.ownerNonce(), "CANCELLED", Map.of("approvalRetryAllowed", false));

        store.observeApproval("u", "s", "signed-token", "UNKNOWN", Map.of("approvalRetryAllowed", true, "reply", "迟到的查询结果"));

        assertThat(lastUpdated.response()).containsEntry("approvalStatus", "CANCELLED")
                .containsEntry("approvalRetryAllowed", false);
        assertThat(store.beginApproval("u", "s", "signed-token", false, true).writable()).isFalse();
    }

    @Test
    void 跨用户或会话命名空间不会借用已接受批次() {
        store.beginApproval("u", "s", "signed-token", true, true);
        assertThat(store.beginApproval("other", "s", "signed-token", false, false).batch()).isNull();
        assertThat(store.beginApproval("u", "other", "signed-token", false, false).batch()).isNull();
    }

    @Test
    void 合法JSON丢失原消息或未知批次接受时间时只读与重试均拒绝读取授权() {
        var valid = store.beginApproval("u", "s", "signed-token", true, true).batch();
        for (var damaged : List.of(
                new ChatHistoryStore.ApprovalBatch("signed-token", null, "UNKNOWN", now, null, 0, null),
                new ChatHistoryStore.ApprovalBatch("signed-token", valid.original(), "UNKNOWN", 0, null, 0, null))) {
            values.replaceAll((key, value) -> mapper.writeValueAsString(damaged));
            assertThatThrownBy(() -> store.beginApproval("u", "s", "signed-token", false, false))
                    .isInstanceOf(ChatHistoryStore.ApprovalRecordUnreadableException.class);
            assertThatThrownBy(() -> store.beginApproval("u", "s", "signed-token", false, true))
                    .isInstanceOf(ChatHistoryStore.ApprovalRecordUnreadableException.class);
        }
    }

    @Test
    void 未接受PENDING账本只允许可信最新消息首次确认而不能显式恢复写() {
        var valid = store.beginApproval("u", "s", "signed-token", true, true).batch();
        var pending = new ChatHistoryStore.ApprovalBatch("signed-token", valid.original(), "PENDING", 0, null, 0, null);
        values.replaceAll((key, value) -> mapper.writeValueAsString(pending));

        assertThat(store.beginApproval("u", "s", "signed-token", false, false).batch().accepted()).isFalse();
        assertThatThrownBy(() -> store.beginApproval("u", "s", "signed-token", false, true))
                .isInstanceOf(IllegalStateException.class);
        var anotherHead = new ChatHistoryStore.StoredMessage("a-new", "assistant", "新的消息", valid.original().at(), valid.original().response());
        when(redis.opsForList().index("chat:hist:u:s", 0)).thenReturn(mapper.writeValueAsString(anotherHead));
        assertThat(store.beginApproval("u", "s", "signed-token", true, true).writable()).isFalse();
        when(redis.opsForList().index("chat:hist:u:s", 0)).thenReturn(original);
        assertThat(store.beginApproval("u", "s", "signed-token", true, true).writable()).isTrue();
    }

    @Test
    void 原消息角色或令牌不匹配属于损坏授权而非可恢复写许可() {
        var valid = store.beginApproval("u", "s", "signed-token", true, true).batch();
        for (var message : List.of(
                new ChatHistoryStore.StoredMessage("a-original", "user", "错误角色", valid.original().at(), valid.original().response()),
                new ChatHistoryStore.StoredMessage("a-original", "assistant", "错误令牌", valid.original().at(), Map.of("approvalToken", "other")))) {
            var damaged = new ChatHistoryStore.ApprovalBatch("signed-token", message, "UNKNOWN", now, null, 0, null);
            values.replaceAll((key, value) -> mapper.writeValueAsString(damaged));
            assertThatThrownBy(() -> store.beginApproval("u", "s", "signed-token", false, true))
                    .isInstanceOf(ChatHistoryStore.ApprovalRecordUnreadableException.class);
        }
    }

    @Test
    void 真实签名与账本共用时钟时崩溃后存在原批次可接管窗口() {
        var tokens = new yumefusaka.envoymart.agent.tool.ApprovalTokens("测试稳定审批密钥", 600, testClock);
        String token = tokens.issue("u", "s", List.of(yumefusaka.envoymart.agent.tool.PendingAction.of("cart_add", Map.of("skuId", 1), "original-op")));
        var message = new ChatHistoryStore.StoredMessage("a-original", "assistant", "原待确认", testClock.instant(),
                Map.of("approvalToken", token, "approvalStatus", "PENDING", "pendingActions", List.of("cart_add")));
        original = mapper.writeValueAsString(message);
        when(redis.opsForList().index("chat:hist:u:s", 0)).thenAnswer(call -> original);
        var first = store.beginApproval("u", "s", token, true, true);
        now += 31000;
        assertThat(tokens.verify(token, "u", "s")).isPresent();
        var restarted = new ChatHistoryStore(redis, mapper, testClock);
        var recovered = restarted.beginApproval("u", "s", token, false, true);
        assertThat(recovered.writable()).isTrue();
        assertThat(recovered.ownerNonce()).isNotEqualTo(first.ownerNonce());
        assertThat(store.renewApproval("u", "s", token, first.ownerNonce())).isFalse();
    }

    @Test
    void 首次接受保存签入恢复时长且原签名过期后只能恢复已接受原批次() {
        var tokens = new yumefusaka.envoymart.agent.tool.ApprovalTokens("测试稳定审批密钥", 600, testClock);
        String token = tokens.issue("u", "s", List.of(yumefusaka.envoymart.agent.tool.PendingAction.of("cart_add", Map.of("skuId", 1), "original-op")));
        var grant = tokens.verifyGrantForRecovery(token, "u", "s").orElseThrow();
        now += 599000;
        original = mapper.writeValueAsString(new ChatHistoryStore.StoredMessage("a-original", "assistant", "原待确认", testClock.instant(),
                Map.of("approvalToken", token, "approvalStatus", "PENDING", "pendingActions", List.of("cart_add"))));
        when(redis.opsForList().index("chat:hist:u:s", 0)).thenAnswer(call -> original);
        var first = store.beginApproval("u", "s", token, true, true, grant.recoverySeconds(), grant.expiresAtSeconds(), true);
        assertThat(first.batch().recoveryUntil()).isEqualTo(now + 86400000L);
        assertThat(lastUpdated.response().get("approvalRecoveryUntil")).isEqualTo(first.batch().recoveryUntil());
        now += 31000;
        assertThat(tokens.verify(token, "u", "s")).isEmpty();
        var recovered = new ChatHistoryStore(redis, mapper, testClock).beginApproval("u", "s", token, false, true,
                grant.recoverySeconds(), grant.expiresAtSeconds(), false);
        assertThat(recovered.writable()).isTrue();
        assertThat(recovered.batch().recoveryUntil()).isEqualTo(first.batch().recoveryUntil());
        assertThat(store.renewApproval("u", "s", token, first.ownerNonce())).isFalse();
        now = first.batch().recoveryUntil() + 1;
        assertThat(store.beginApproval("u", "s", token, false, true, grant.recoverySeconds(), grant.expiresAtSeconds(), false).writable()).isFalse();
    }

    @Test
    void 未接受的过期签名不能因携带恢复时长获得首次写权限() {
        assertThat(store.beginApproval("u", "s", "signed-token", true, true, 86400, now / 1000 - 1, false).writable()).isFalse();
        assertThat(values).isEmpty();
    }

    @Test
    void 恢复截止或接受时间被篡改不能借合法JSON恢复写权限() {
        long expires = now / 1000 + 600;
        var accepted = store.beginApproval("u", "s", "signed-token", true, true, 86400, expires, true).batch();
        for (var damaged : List.of(
                new ChatHistoryStore.ApprovalBatch(accepted.token(), accepted.original(), "UNKNOWN", now, null, 0, null, 0),
                new ChatHistoryStore.ApprovalBatch(accepted.token(), accepted.original(), "UNKNOWN", now, null, 0, null, now + 86400001),
                new ChatHistoryStore.ApprovalBatch(accepted.token(), accepted.original(), "UNKNOWN", expires * 1000 + 1000, null, 0, null,
                        expires * 1000 + 1000 + 86400000))) {
            values.replaceAll((key, value) -> mapper.writeValueAsString(damaged));
            assertThatThrownBy(() -> store.beginApproval("u", "s", "signed-token", false, true, 86400, expires, true))
                    .isInstanceOf(ChatHistoryStore.ApprovalRecordUnreadableException.class);
        }
    }

    @Test
    void 旧签名仅在原期限恢复且不会取得二十四小时授权() {
        long expires = now / 1000 + 600;
        var first = store.beginApproval("u", "s", "signed-token", true, true, 0, expires, true);
        assertThat(first.batch().recoveryUntil()).isEqualTo(expires * 1000 + 999);
        now += 31000;
        assertThat(store.beginApproval("u", "s", "signed-token", false, true, 0, expires, true).writable()).isTrue();
        now = expires * 1000 + 1000;
        assertThat(store.beginApproval("u", "s", "signed-token", false, true, 0, expires, false).writable()).isFalse();
    }

    @Test
    void 旧owner到期不可续租或完成而未决可被接管取消删除禁止续写() {
        long expires = now / 1000 + 600;
        var first = store.beginApproval("u", "s", "signed-token", true, true, 86400, expires, true);
        now += 31000;
        assertThat(store.renewApproval("u", "s", "signed-token", first.ownerNonce())).isFalse();
        assertThat(store.finishApproval("u", "s", "signed-token", first.ownerNonce(), "CONFIRMED", Map.of("reply", "迟到"))).isFalse();
        var next = store.beginApproval("u", "s", "signed-token", false, true, 86400, expires, true);
        assertThat(next.writable()).isTrue();
        assertThat(store.finishApproval("u", "s", "signed-token", next.ownerNonce(), "CANCELLED", Map.of("reply", "已停止"))).isTrue();
        assertThat(store.beginApproval("u", "s", "signed-token", false, true, 86400, expires, true).writable()).isFalse();
        when(redis.hasKey("chat:deleted:u:s")).thenReturn(true);
        assertThat(store.renewApproval("u", "s", "signed-token", next.ownerNonce())).isFalse();
        assertThat(store.beginApproval("u", "s", "signed-token", false, false, 86400, expires, true).batch().cancelled()).isTrue();
        assertThatThrownBy(() -> store.beginApproval("u", "s", "signed-token", false, true, 86400, expires, true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 已完成账本查询不能降回未知以重新打开取消或新owner入口() {
        var first = store.beginApproval("u", "s", "signed-token", true, true);
        assertThat(store.finishApproval("u", "s", "signed-token", first.ownerNonce(), "CONFIRMED", Map.of("reply", "完成"))).isTrue();
        store.observeApproval("u", "s", "signed-token", "UNKNOWN", Map.of("reply", "查询暂不可达"));
        assertThat(store.beginApproval("u", "s", "signed-token", false, false).batch().status()).isEqualTo("CONFIRMED");
    }

    @Test
    void 后续卡发布必须匹配已完成账本无墓碑且只发布一次() {
        var first = store.beginApproval("u", "s", "signed-token", true, true);
        Map<String, Object> next = Map.of("approvalToken", "next-token", "approvalStatus", "PENDING", "reply", "请确认下一操作",
                "turnId", "turn-next", "userMessageId", "u-next", "assistantMessageId", "a-next", "pendingActions", List.of("cart_checkout"));
        assertThat(store.publishNextApproval("u", "s", "signed-token", "确认", next)).isFalse();
        assertThat(store.finishApproval("u", "s", "signed-token", first.ownerNonce(), "CONFIRMED", Map.of("reply", "完成"))).isTrue();
        when(redis.hasKey("chat:deleted:u:s")).thenReturn(true);
        assertThat(store.publishNextApproval("u", "s", "signed-token", "确认", next)).isFalse();
        when(redis.hasKey("chat:deleted:u:s")).thenReturn(false);
        assertThat(store.publishNextApproval("u", "s", "signed-token", "确认", next)).isTrue();
        assertThat(lastUpdated.id()).isEqualTo("a-next");
        assertThat(lastUpdated.response().get("approvalToken")).isEqualTo("next-token");
        assertThat(store.publishNextApproval("u", "s", "signed-token", "确认", next)).isFalse();
    }
}
