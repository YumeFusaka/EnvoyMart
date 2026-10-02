package yumefusaka.envoymart.aiservice.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「重新生成」落历史的形态：<b>就地改写最后一条助手答复，而不是追加一轮。</b>
 * <p>
 * 界面上重新生成是一个原地替换的动作，历史必须同构——追加的话点一次就多一对
 * 完全重复的问答，点三次侧栏里挂着一串一模一样的提问。判据取「列表最新的一条
 * 是助手消息」而不是「盲改 index 0」：最新一条不是助手消息（半轮孤本、异常状态）
 * 时退回追加，宁可多一条，也不能把一条用户消息覆盖成助手消息。
 * <p>
 * 会话删除的墓碑对改写同样生效——用户删掉会话时这一轮的生成可能还在跑，
 * 重新生成不该是绕过墓碑的后门。
 */
class ChatHistoryRewriteTest {

    private static final String USER = "u-1";
    private static final String SESSION = "u-1-1759280000000";
    private static final String HIST_KEY = "chat:hist:" + USER + ":" + SESSION;
    private static final String TOMBSTONE = "chat:deleted:" + USER + ":" + SESSION;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StringRedisTemplate redis;
    /** 回调内部执行写入的句柄：multi/exec 里发过什么命令，只能从这里看 */
    private ListOperations<String, String> writeOps;
    /** 模板直连的读句柄（range 等） */
    private ListOperations<String, String> readOps;
    private ChatHistoryStore store;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redis = mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS);
        readOps = redis.opsForList();

        // 深桩下 redis.execute(callback) 不会真的执行回调，被测的 MULTI 也就无从观察。
        // 这里把回调捞出来对着一个深桩 RedisOperations 真跑一遍，写入决策才可断言
        RedisOperations<String, String> ops = mock(RedisOperations.class, Answers.RETURNS_DEEP_STUBS);
        writeOps = ops.opsForList();
        doAnswer(invocation -> {
            SessionCallback callback = invocation.getArgument(0);
            callback.execute(ops);
            return null;
        }).when(redis).execute(any(SessionCallback.class));

        store = new ChatHistoryStore(redis, MAPPER);
    }

    private String stored(String id, String role, String content) {
        return MAPPER.writeValueAsString(
                new ChatHistoryStore.StoredMessage(id, role, content, Instant.now(), null));
    }

    @Test
    void 最新一条是助手消息时就地改写而不是追加() throws Exception {
        when(readOps.range(HIST_KEY, 0, 0)).thenReturn(List.of(stored("a-old", "assistant", "旧答复")));

        store.recordAnswer(USER, SESSION, "新答复", null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(writeOps).set(eq(HIST_KEY), eq(0L), payload.capture());
        ChatHistoryStore.StoredMessage rewritten =
                MAPPER.readValue(payload.getValue(), ChatHistoryStore.StoredMessage.class);
        assertThat(rewritten.role()).isEqualTo("assistant");
        assertThat(rewritten.content())
                .as("落进历史的是新答复——否则重新生成等于把旧答案又存了一遍")
                .isEqualTo("新答复");
        verify(writeOps, never())
                .leftPushAll(anyString(), anyList());
    }

    @Test
    void 最新一条不是助手消息时退回追加() {
        when(readOps.range(HIST_KEY, 0, 0)).thenReturn(List.of(stored("u-1", "user", "我的问题")));

        store.recordAnswer(USER, SESSION, "新答复", null);

        verify(writeOps).leftPushAll(eq(HIST_KEY), anyList());
        // 宁可多一条答复，也不能把用户自己的提问覆盖成助手消息
        verify(writeOps, never()).set(anyString(), anyLong(), anyString());
    }

    @Test
    void 历史为空时同样退回追加() {
        when(readOps.range(HIST_KEY, 0, 0)).thenReturn(List.of());

        store.recordAnswer(USER, SESSION, "新答复", null);

        verify(writeOps).leftPushAll(eq(HIST_KEY), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 已删会话的改写被丢弃() {
        when(redis.hasKey(TOMBSTONE)).thenReturn(true);

        store.recordAnswer(USER, SESSION, "新答复", null);

        // 一条命令都不该发出去 —— 改写是把删掉的东西写回来的另一条路
        verify(redis, never()).execute(any(SessionCallback.class));
    }
}
