package yumefusaka.envoymart.aiservice.memory;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.memory.MemoryItem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「重新生成」在 Redis 窗口里的丢弃语义。
 * <p>
 * 与内存实现同一份判据（末尾那条是不是助手答复），这里额外钉住两件事：
 * ① 判据看的是<b>内容</b>而不是条数——按条数硬丢会误删上一轮的正经内容；
 * ② 丢弃**不续 TTL**——丢弃不是「这一轮发生过对话」的证据。
 */
class RedisShortTermMemoryDropLastTurnTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ListOperations<String, String> listOps = mock(ListOperations.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private RedisShortTermMemoryStore store() {
        when(redis.opsForList()).thenReturn(listOps);
        return new RedisShortTermMemoryStore(redis, mapper);
    }

    private String stored(String content) {
        return mapper.writeValueAsString(java.util.Map.of(
                "id", "m-" + content.hashCode(),
                "userId", "u1",
                "content", content,
                "type", "MESSAGE",
                "timestamp", Instant.now().toString()));
    }

    @Test
    void 头部是助手答复时一次丢两条() {
        RedisShortTermMemoryStore store = store();
        List<String> head = new ArrayList<>(List.of(
                stored("assistant: 我并没有真正检索到这些条款"),
                stored("user: 平台的满减活动规则是什么？")));
        when(listOps.range(anyString(), eq(0L), eq(1L))).thenReturn(head);

        assertThat(store.dropLastTurn("s1")).isTrue();

        // 头部两条（0=助手答复、1=它对应的用户消息）一起弹出
        verify(listOps).trim(anyString(), eq(2L), eq(-1L));
    }

    @Test
    void 头部不是助手答复时只丢一条() {
        RedisShortTermMemoryStore store = store();
        List<String> head = new ArrayList<>(List.of(stored("user: 这一轮只落了用户消息")));
        when(listOps.range(anyString(), eq(0L), eq(1L))).thenReturn(head);

        assertThat(store.dropLastTurn("s1")).isFalse();

        // 只丢这一条——按条数硬丢两条会误删上一轮的助手答复
        verify(listOps).trim(anyString(), eq(1L), eq(-1L));
    }

    @Test
    void 头部是孤儿助手行时只丢一条() {
        RedisShortTermMemoryStore store = store();
        // 确认回执只写助手一条：它前面那条是上一轮的助手答复，不是它配对的用户消息
        List<String> head = new ArrayList<>(List.of(
                stored("assistant: 已加入购物车"),
                stored("assistant: 这个操作不可撤销，请确认")));
        when(listOps.range(anyString(), eq(0L), eq(1L))).thenReturn(head);

        assertThat(store.dropLastTurn("s1")).isFalse();

        // 照旧 trim 2 会把上一轮出卡那条助手行也删掉
        verify(listOps).trim(anyString(), eq(1L), eq(-1L));
    }

    @Test
    void 头部助手行前一条是用户消息时才算完整一轮() {
        RedisShortTermMemoryStore store = store();
        List<String> head = new ArrayList<>(List.of(
                stored("assistant: 已加入购物车"),
                stored("user: 确认执行")));
        when(listOps.range(anyString(), eq(0L), eq(1L))).thenReturn(head);

        assertThat(store.dropLastTurn("s1")).isTrue();

        verify(listOps).trim(anyString(), eq(2L), eq(-1L));
    }

    @Test
    void 丢弃不续期窗口TTL() {
        RedisShortTermMemoryStore store = store();
        when(listOps.range(anyString(), eq(0L), eq(1L))).thenReturn(new ArrayList<>(List.of(
                stored("assistant: 上一轮答复"), stored("user: 上一轮问题"))));

        store.dropLastTurn("s1");

        // 丢弃不是「这一轮发生过对话」的证据：若生成阶段失败，续期会让没人用的窗口又活一遍
        verify(redis, never()).expire(anyString(), anyLong(), any());
    }

    @Test
    void 空窗口丢弃返回false且不报错() {
        RedisShortTermMemoryStore store = store();
        when(listOps.range(anyString(), eq(0L), eq(1L))).thenReturn(List.of());

        assertThat(store.dropLastTurn("s1")).isFalse();
        verify(listOps, never()).trim(anyString(), anyLong(), anyLong());
    }

    @Test
    void redis异常时降级为false不抛出() {
        RedisShortTermMemoryStore store = store();
        when(listOps.range(anyString(), eq(0L), eq(1L))).thenThrow(new RuntimeException("连接超时"));

        assertThat(store.dropLastTurn("s1")).isFalse();
    }
}
