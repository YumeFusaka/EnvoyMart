package yumefusaka.envoymart.aiservice.memory;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.memory.MemoryItem;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话窗口降级的可观测性。
 * <p>
 * 降级本身是设计内行为（Redis 抖动时正确的做法是"这一轮没有历史"而不是对话失败），
 * 但它必须<b>看得见</b>——原实现只打一行 WARN，日志回答不了"最近是不是一直在降级"，
 * 也没有告警的挂点。这个测试把「读写各一个计数、且只有失败才计数」钉住，
 * 防止以后重构把埋点悄悄丢掉。
 */
class RedisShortTermMemoryStoreDegradeTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ListOperations<String, String> listOps = mock(ListOperations.class);

    @Test
    void 读失败记load写失败记append且正常路径不计数() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(redis.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("连接超时"));
        when(listOps.leftPush(anyString(), anyString()))
                .thenThrow(new RuntimeException("连接超时"));

        RedisShortTermMemoryStore store =
                new RedisShortTermMemoryStore(redis, new ObjectMapper(), registry);

        assertThat(store.load("s1", 10)).as("读失败要降级为空列表，不抛异常").isEmpty();
        store.append("s1", MemoryItem.builder()
                .id("m1").sessionId("s1").content("你好")
                .timestamp(Instant.now()).build(), 10);

        assertThat(registry.get("agent.stm.degraded").tag("op", "load").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("agent.stm.degraded").tag("op", "append").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void 不注入registry时功能照常降级不报错() {
        when(redis.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("连接超时"));

        RedisShortTermMemoryStore store = new RedisShortTermMemoryStore(redis, new ObjectMapper());

        assertThat(store.load("s1", 10)).isEmpty();
    }
}
