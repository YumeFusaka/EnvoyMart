package yumefusaka.envoymart.aiservice.memory;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请求级幂等的判据。
 * <p>
 * 这一层最容易写错的地方是「用什么当键」：拿消息内容当键会把用户
 * 「过一会儿想再问一遍」误吞；拿 sessionId 当键会把同一会话里的连续提问全判成重复。
 * 判据只能是网关发的请求号。
 */
class ChatIdempotencyStoreTest {

    private record Payload(String reply) {
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> ops(StringRedisTemplate redis) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        return ops;
    }

    @Test
    void 首次到达占位成功() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = ops(redis);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        ChatIdempotencyStore store = new ChatIdempotencyStore(redis, new ObjectMapper());

        assertThat(store.tryAcquire("u1001", "abc123")).isTrue();
    }

    @Test
    void 窗口内重复到达占位失败() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = ops(redis);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        ChatIdempotencyStore store = new ChatIdempotencyStore(redis, new ObjectMapper());

        assertThat(store.tryAcquire("u1001", "abc123"))
                .as("同一请求号再来一次就是同一次提问被送了两遍")
                .isFalse();
    }

    @Test
    void 不同请求号互不影响() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = ops(redis);
        when(ops.setIfAbsent(eq("chat:idem:u1001:a1"), anyString(), any(Duration.class))).thenReturn(true);
        when(ops.setIfAbsent(eq("chat:idem:u1001:a2"), anyString(), any(Duration.class))).thenReturn(true);

        ChatIdempotencyStore store = new ChatIdempotencyStore(redis, new ObjectMapper());

        assertThat(store.tryAcquire("u1001", "a1")).isTrue();
        assertThat(store.tryAcquire("u1001", "a2"))
                .as("用户再问一次是新请求，必须放行——幂等只能按请求号判，不能按内容判")
                .isTrue();
    }

    @Test
    void 没有请求号时放行() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ops(redis);

        ChatIdempotencyStore store = new ChatIdempotencyStore(redis, new ObjectMapper());

        assertThat(store.tryAcquire("u1001", null)).isTrue();
        assertThat(store.tryAcquire("u1001", "  ")).isTrue();
        verify(redis, never()).opsForValue();
    }

    @Test
    void Redis不可用时照常执行() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RuntimeException("连接被拒绝"));

        ChatIdempotencyStore store = new ChatIdempotencyStore(redis, new ObjectMapper());

        assertThat(store.tryAcquire("u1001", "abc123"))
                .as("幂等是防重复；fail-closed 会变成「缓存挂了就不能提问」，拿可用性换概率性防护")
                .isTrue();
    }

    @Test
    void 占位仍在进行中时读不到结果() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = ops(redis);
        when(ops.get("chat:idem:u1001:abc123")).thenReturn("PENDING");

        ChatIdempotencyStore store = new ChatIdempotencyStore(redis, new ObjectMapper());

        assertThat(store.previous("u1001", "abc123", Payload.class)).isEmpty();
    }

    @Test
    void 完成后能读回上一次的结果() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = ops(redis);
        ObjectMapper mapper = new ObjectMapper();
        when(ops.get("chat:idem:u1001:abc123")).thenReturn(mapper.writeValueAsString(new Payload("上次的回答")));

        ChatIdempotencyStore store = new ChatIdempotencyStore(redis, mapper);

        Optional<Payload> previous = store.previous("u1001", "abc123", Payload.class);
        assertThat(previous).isPresent();
        assertThat(previous.get().reply()).isEqualTo("上次的回答");
    }
}
