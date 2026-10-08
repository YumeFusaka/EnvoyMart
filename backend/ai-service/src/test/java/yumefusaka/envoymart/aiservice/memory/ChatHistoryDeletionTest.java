package yumefusaka.envoymart.aiservice.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「删除会话」这条语义的钉子。
 * <p>
 * 删除撞上生成是实测复现过的缺陷：用户删掉会话时服务端那一轮还在跑（SSE 断开不打断生成），
 * 跑完会把整段历史连同标题写回来 —— 侧栏里删掉的会话带着新内容重新出现。
 * 修复是墓碑：删除时立键，写入前后各查一次。这里钉住的就是那两次检查与删除的返回值语义。
 * <p>
 * 用 RETURNS_DEEP_STUBS 而不是逐个手写假实现：被测的是「哪条路径被走到」，
 * 不是 Redis 命令的拼装细节，桩太深反而把测试写成对实现的复述。
 * 深桩在 verify() 里不生效（校验时不返回桩），所以操作句柄要在 setUp 里先取出来。
 */
class ChatHistoryDeletionTest {

    private static final String USER = "u-1";
    private static final String SESSION = "u-1-1759280000000";
    private static final String TOMBSTONE = "chat:deleted:" + USER + ":" + SESSION;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ZSetOperations<String, String> zset;
    private ChatHistoryStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS);
        valueOps = redis.opsForValue();
        zset = redis.opsForZSet();
        store = new ChatHistoryStore(redis, new ObjectMapper());
    }

    @Test
    void 已删会话的落盘被丢弃() {
        when(redis.hasKey(TOMBSTONE)).thenReturn(true);

        store.recordTurn(USER, SESSION, "帮我取消订单", "好的", null);

        // 一条命令都不该发出去 —— 发出去就是把用户删掉的东西写回来
        verify(redis, never()).execute(any(SessionCallback.class));
    }

    @Test
    void 落盘期间被删则回滚本次写入() {
        // 入口查时还没删（那一轮在跑），写完再查时墓碑已经立起来了
        when(redis.hasKey(TOMBSTONE)).thenReturn(false, true);

        store.recordTurn(USER, SESSION, "帮我取消订单", "好的", null);

        verify(redis).delete(List.of("chat:hist:" + USER + ":" + SESSION, "chat:meta:" + USER + ":" + SESSION));
        verify(zset).remove("chat:sessions:" + USER, SESSION);
    }

    @Test
    void 只有历史与元数据被删到也算删成功() {
        // 索引项上一次写入只成功了一半时会出现这种状态：数据还在、索引没了。
        // 只看索引会回 404「不存在」，可数据明明刚被这次调用清掉 —— 状态码与事实相反
        when(zset.remove(anyString(), any())).thenReturn(0L);
        when(redis.delete(any(List.class))).thenReturn(2L);

        assertThat(store.delete(USER, SESSION)).isTrue();
        verify(valueOps).set(TOMBSTONE, "1", Duration.ofDays(7));
    }

    @Test
    void 什么都没删到才算未删除() {
        when(zset.remove(anyString(), any())).thenReturn(0L);
        when(redis.delete(any(List.class))).thenReturn(0L);

        assertThat(store.delete(USER, SESSION)).isFalse();
        // 墓碑照立：删除请求即使没删到东西，也不该让在飞的那一轮再写回来
        verify(valueOps).set(TOMBSTONE, "1", Duration.ofDays(7));
    }

    @Test
    void 已确认的记录不能取消() {
        var message = new ChatHistoryStore.StoredMessage("a-test", "assistant", "请确认", java.time.Instant.now(),
                java.util.Map.of("pendingActions", List.of("cart_add"), "approvalStatus", "CONFIRMED"));
        when(redis.opsForList().range("chat:hist:" + USER + ":" + SESSION, 0, 199L))
                .thenReturn(List.of(new ObjectMapper().writeValueAsString(message)));
        assertThat(store.dismissApproval(USER, SESSION, "a-test")).isFalse();
    }

    @Test
    void 已取消记录不再提供确认授权() {
        var message = new ChatHistoryStore.StoredMessage("a-test", "assistant", "已取消", java.time.Instant.now(),
                java.util.Map.of("pendingActions", List.of("cart_add"), "approvalStatus", "DISMISSED"));
        when(redis.opsForList().index("chat:hist:" + USER + ":" + SESSION, 0))
                .thenReturn(new ObjectMapper().writeValueAsString(message));
        assertThat(store.claimApproval(USER, SESSION, "old-token")).isFalse();
    }
}
