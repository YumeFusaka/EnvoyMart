package yumefusaka.envoymart.aiservice.memory;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.core.TaskStage;
import yumefusaka.envoymart.agent.core.task.TaskCheckpoint;
import yumefusaka.envoymart.agent.core.task.TaskStateStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 断点存储的失败立场。
 * <p>
 * 这一层要钉住的核心不是「能不能存」，而是<b>存不下时不许抛</b>——
 * 断点是优化，存储层抖动不该把一次正常的中断变成报错。
 */
class RedisTaskStateStoreTest {

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> ops(StringRedisTemplate redis) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        return ops;
    }

    private static TaskCheckpoint checkpoint() {
        return new TaskCheckpoint("u1:s1", "u1", "s1", TaskStage.WAITING_USER, "取消订单",
                List.of("order_query"),
                List.of(new TaskCheckpoint.PendingCall("order_cancel", Map.of("orderId", 12))),
                1, 0L);
    }

    @Test
    void 保存后能原样读回() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = ops(redis);
        RedisTaskStateStore store = new RedisTaskStateStore(redis, new ObjectMapper());

        store.save(checkpoint());
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ops).set(anyString(), captor.capture(), any(java.time.Duration.class));
        when(ops.get(anyString())).thenReturn(captor.getValue());

        Optional<TaskCheckpoint> loaded = store.load("u1", "u1:s1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().stage()).isEqualTo(TaskStage.WAITING_USER);
        assertThat(loaded.get().pendingActions()).hasSize(1);
        assertThat(loaded.get().pendingActions().get(0).tool()).isEqualTo("order_cancel");
        assertThat(loaded.get().resumable()).isTrue();
    }

    @Test
    void 保存失败时不抛出异常() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = ops(redis);
        // set() 返回 void，桩「抛异常」要用 doThrow().when() 形式：
        // when(void 调用) 在编译期就不成立（javac 报的是「此处不允许使用空类型」）
        org.mockito.Mockito.doThrow(new RuntimeException("Redis 抖动"))
                .when(ops).set(anyString(), anyString(), any(java.time.Duration.class));
        RedisTaskStateStore store = new RedisTaskStateStore(redis, new ObjectMapper());

        // 断言的是「不抛出」，这正是这一层存在的意义
        store.save(checkpoint());
    }

    @Test
    void 读取失败时返回空而不是上抛() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = ops(redis);
        when(ops.get(anyString())).thenThrow(new RuntimeException("Redis 抖动"));
        RedisTaskStateStore store = new RedisTaskStateStore(redis, new ObjectMapper());

        assertThat(store.load("u1", "u1:s1")).isEmpty();
    }

    @Test
    void 读到损坏的JSON时返回空而不是报错() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = ops(redis);
        when(ops.get(anyString())).thenReturn("{ 不是合法 JSON");
        RedisTaskStateStore store = new RedisTaskStateStore(redis, new ObjectMapper());

        assertThat(store.load("u1", "u1:s1")).isEmpty();
    }

    @Test
    void 清除失败时不影响调用方() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.delete(anyString())).thenThrow(new RuntimeException("Redis 抖动"));
        RedisTaskStateStore store = new RedisTaskStateStore(redis, new ObjectMapper());

        store.clear("u1", "u1:s1");
    }

    @Test
    void 空标识不产生任何调用() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisTaskStateStore store = new RedisTaskStateStore(redis, new ObjectMapper());

        assertThat(store.load(null, "t")).isEmpty();
        assertThat(store.load("u", null)).isEmpty();
        store.save(null);
        store.clear(null, null);
        org.mockito.Mockito.verify(redis, org.mockito.Mockito.never()).opsForValue();
    }

    @Test
    void NOOP实现永远返回空() {
        TaskStateStore noop = TaskStateStore.NOOP;
        noop.save(checkpoint());
        assertThat(noop.load("u1", "u1:s1")).isEmpty();
        noop.clear("u1", "u1:s1");
    }
}
