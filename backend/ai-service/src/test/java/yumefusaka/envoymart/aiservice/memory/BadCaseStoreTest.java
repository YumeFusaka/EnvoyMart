package yumefusaka.envoymart.aiservice.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BadCaseStoreTest {

    private static final String USER = "u-1";
    private static final String SESSION = "u-1-session";
    private static final String HIST_KEY = "chat:hist:" + USER + ":" + SESSION;

    private final ObjectMapper mapper = new ObjectMapper();
    private StringRedisTemplate redis;
    private ListOperations<String, String> list;
    private BadCaseStore store;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS);
        list = redis.opsForList();
        store = new BadCaseStore(redis, mapper);
    }

    @Test
    void 反馈可以选择当前会话的完整消息而不是固定窗口() throws Exception {
        ChatHistoryStore.StoredMessage firstUser = message("u-1", "user", "第一轮问题");
        ChatHistoryStore.StoredMessage firstAssistant = message("a-1", "assistant", "第一轮回答");
        ChatHistoryStore.StoredMessage currentUser = message("u-2", "user", "当前问题");
        ChatHistoryStore.StoredMessage currentAssistant = message("a-2", "assistant", "当前回答");
        // Redis 以新消息在头部存储，helper 读出后会恢复时间正序。
        when(list.range(HIST_KEY, 0, 199L)).thenReturn(List.of(
                mapper.writeValueAsString(currentAssistant), mapper.writeValueAsString(currentUser),
                mapper.writeValueAsString(firstAssistant), mapper.writeValueAsString(firstUser)));

        BadCaseStore.BadCase saved = store.upsert(USER, SESSION, "a-2",
                new BadCaseStore.FeedbackRequest(
                        List.of(BadCaseStore.Reason.FACT_ERROR),
                        List.of("u-1", "a-1", "u-2", "a-2"), "前后文都相关"));

        assertThat(saved.assistantMessageId()).isEqualTo("a-2");
        assertThat(saved.userMessageId()).isEqualTo("u-2");
        assertThat(saved.selectedMessageIds()).containsExactly("u-1", "a-1", "u-2", "a-2");
    }

    @Test
    void 选择不属于当前用户会话的消息会被拒绝() throws Exception {
        ChatHistoryStore.StoredMessage user = message("u-2", "user", "问题");
        ChatHistoryStore.StoredMessage assistant = message("a-2", "assistant", "回答");
        when(list.range(HIST_KEY, 0, 199L)).thenReturn(List.of(
                mapper.writeValueAsString(assistant), mapper.writeValueAsString(user)));

        assertThatThrownBy(() -> store.upsert(USER, SESSION, "a-2",
                new BadCaseStore.FeedbackRequest(List.of(BadCaseStore.Reason.OTHER),
                        List.of("a-from-another-session"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("关联消息不属于当前会话");
    }

    @Test
    void 撤销后重新提交会恢复为活动状态() throws Exception {
        ChatHistoryStore.StoredMessage user = message("u-2", "user", "问题");
        ChatHistoryStore.StoredMessage assistant = message("a-2", "assistant", "回答");
        when(list.range(HIST_KEY, 0, 199L)).thenReturn(List.of(
                mapper.writeValueAsString(assistant), mapper.writeValueAsString(user)));
        String key = "chat:badcase:" + USER + ":" + SESSION + ":a-2";
        BadCaseStore.BadCase revoked = new BadCaseStore.BadCase(
                "bc-1", USER, SESSION, "a-2", "u-2", List.of("u-2", "a-2"),
                List.of(BadCaseStore.Reason.OTHER), null, "问题", "回答", Map.of(),
                Instant.now(), Instant.now(), BadCaseStore.Status.REVOKED, null, null, null);
        when(redis.opsForValue().get(key)).thenReturn(mapper.writeValueAsString(revoked));

        BadCaseStore.BadCase saved = store.upsert(USER, SESSION, "a-2",
                new BadCaseStore.FeedbackRequest(List.of(BadCaseStore.Reason.OTHER),
                        List.of("u-2", "a-2"), null));

        assertThat(saved.status()).isEqualTo(BadCaseStore.Status.ACTIVE);
    }

    private ChatHistoryStore.StoredMessage message(String id, String role, String content) {
        return new ChatHistoryStore.StoredMessage(id, role, content, Instant.now(), null);
    }
}
