package yumefusaka.envoymart.agent.memory;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * token 预算的契约。
 * <p>
 * 这里锁的不是"估得准不准"——估算刻意高估，本来就不准。锁的是两件事：
 * <b>估算方向必须是高估</b>（低估会让预算形同虚设），
 * <b>裁剪必须从最旧的开始</b>（指代链向后，丢最旧的损失最小）。
 */
class ContextBudgetTest {

    private static ChatMessage user(String content) {
        return ChatMessage.builder().role(ChatMessage.Role.USER).content(content).build();
    }

    @Test
    void 汉字按一字一token估算() {
        assertThat(ContextBudget.estimate("你好世界")).isEqualTo(4);
    }

    /**
     * 英文按 3 字符 1 token 估，实际约 4 字符 1 token——高估方向不能反过来。
     * 一旦这类断言失败，说明有人把除数调大了，那等于把预算放宽。
     */
    @Test
    void 英文估算不低于实际() {
        String english = "The quick brown fox jumps over the lazy dog";
        assertThat(ContextBudget.estimate(english))
                .as("英文按 3 字符 1 token 估，比实际约 4 字符 1 token 更保守")
                .isGreaterThanOrEqualTo(english.length() / 4);
    }

    @Test
    void 空输入为零() {
        assertThat(ContextBudget.estimate((String) null)).isZero();
        assertThat(ContextBudget.estimate("")).isZero();
        assertThat(ContextBudget.estimate(List.of())).isZero();
    }

    @Test
    void 未超预算时原样返回() {
        List<ChatMessage> history = List.of(user("你好"), user("在吗"));

        assertThat(ContextBudget.fit(history, 1000)).isSameAs(history);
    }

    @Test
    void 超预算时从最旧的开始丢() {
        List<ChatMessage> history = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> user("第" + i + "轮".repeat(20)))
                .toList();

        List<ChatMessage> fitted = ContextBudget.fit(history, 40);

        assertThat(fitted).isNotEmpty().hasSizeLessThan(history.size());
        assertThat(fitted.getLast())
                .as("最近一条必须留下——它离当前发言最近")
                .isEqualTo(history.getLast());
        assertThat(fitted.getFirst())
                .as("丢掉的是最旧的那些")
                .isNotEqualTo(history.getFirst());
    }

    /**
     * 只剩一条时不再丢，哪怕它自己就超预算。
     * <p>
     * 那一条是"上一轮说了什么"，多轮对话里最不能丢的就是它；
     * 至于它本身很长——那是用户自己贴的，该由入口的长度校验管，
     * 不该在这里被静默截成半句话。
     */
    @Test
    void 至少保留最近一条() {
        List<ChatMessage> history = List.of(user("旧"), user("很长".repeat(5000)));

        List<ChatMessage> fitted = ContextBudget.fit(history, 10);

        assertThat(fitted).hasSize(1);
        assertThat(fitted.getFirst().getContent()).isEqualTo("很长".repeat(5000));
    }

    @Test
    void 空历史返回空列表() {
        assertThat(ContextBudget.fit(List.of(), 100)).isEmpty();
        assertThat(ContextBudget.fit(null, 100)).isEmpty();
    }
}
