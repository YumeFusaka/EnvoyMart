package yumefusaka.envoymart.aiservice.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话标题规则的钉子。
 * <p>
 * 标题会原样出现在侧栏，模型与用户都可能塞进超长文本或多行输入，
 * 截断边界与换行压平是两个实际会坏的点。
 */
class ChatHistoryStoreTest {

    @Test
    void 短消息原样作标题() {
        assertThat(ChatHistoryStore.titleOf("七天无理由退货怎么处理")).isEqualTo("七天无理由退货怎么处理");
    }

    @Test
    void 多行输入压成一行() {
        assertThat(ChatHistoryStore.titleOf("帮我看看\n这个订单\n到哪了")).isEqualTo("帮我看看 这个订单 到哪了");
    }

    @Test
    void 超长消息截断并加省略号() {
        String message = "一".repeat(30);
        String title = ChatHistoryStore.titleOf(message);
        assertThat(title).isEqualTo("一".repeat(24) + "…");
        assertThat(title.length()).isEqualTo(25);
    }

    @Test
    void 空白与空值回退为默认标题() {
        assertThat(ChatHistoryStore.titleOf(null)).isEqualTo("新对话");
        assertThat(ChatHistoryStore.titleOf("   \n\t ")).isEqualTo("新对话");
    }

    @Test
    void 每轮身份由服务端生成且三项互相对应() {
        ChatHistoryStore.TurnIdentity first = ChatHistoryStore.newTurnIdentity();
        ChatHistoryStore.TurnIdentity second = ChatHistoryStore.newTurnIdentity();

        assertThat(first.turnId()).startsWith("turn-");
        assertThat(first.userMessageId()).startsWith("u-");
        assertThat(first.assistantMessageId()).startsWith("a-");
        assertThat(first.turnId()).isNotEqualTo(second.turnId());
        assertThat(first.turnId().substring("turn-".length()))
                .isEqualTo(first.userMessageId().substring("u-".length()))
                .isEqualTo(first.assistantMessageId().substring("a-".length()));
    }
}
