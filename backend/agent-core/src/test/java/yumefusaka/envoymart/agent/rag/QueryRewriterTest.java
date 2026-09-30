package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.MockLLMProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指代消解改写的边界：<b>该改的改、不该改的不动、改坏的一律退回原句。</b>
 * <p>
 * 这些边界比「改写得准不准」更重要：改写是检索侧唯一一次模型参与，
 * 它出了错不会报异常，只会让检索悄悄换一批证据——一个被改坏的查询
 * 拿到的是错误证据（自信地答错），比拿不到证据（拒答）更糟。
 */
class QueryRewriterTest {

    private static final LLMConfig CONFIG = LLMConfig.builder().model("stub").build();

    /** 按脚本应答的桩：固定返回一段文本（或抛异常），并记录收到的调用次数与消息 */
    private static class StubProvider implements LLMProvider {
        final AtomicInteger calls = new AtomicInteger();
        List<ChatMessage> lastMessages;
        private final String reply;
        private final RuntimeException boom;

        StubProvider(String reply) {
            this(reply, null);
        }

        StubProvider(String reply, RuntimeException boom) {
            this.reply = reply;
            this.boom = boom;
        }

        @Override
        public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            calls.incrementAndGet();
            lastMessages = messages;
            if (boom != null) {
                throw boom;
            }
            return LLMResponse.builder().content(reply).build();
        }
    }

    private static List<ChatMessage> history() {
        return List.of(
                ChatMessage.builder().role(ChatMessage.Role.USER).content("维生素D3 每天吃多少合适").build(),
                ChatMessage.builder().role(ChatMessage.Role.ASSISTANT).content("成人建议每日 400-800IU [1]").build());
    }

    @Test
    void 首轮没有历史时不调用模型并原样返回() {
        StubProvider llm = new StubProvider("维生素D3 每日摄入量");
        QueryRewriter rewriter = new QueryRewriter(llm, CONFIG);

        String result = rewriter.rewrite("维生素D3 每天吃多少合适", List.of());

        assertThat(result).isEqualTo("维生素D3 每天吃多少合适");
        assertThat(llm.calls.get()).as("首轮没有可消解的指代，改写必须是零成本").isZero();
    }

    @Test
    void 有历史时用改写结果且提示词里带着历史() {
        StubProvider llm = new StubProvider("维生素D3 与钙片 能否同服");
        QueryRewriter rewriter = new QueryRewriter(llm, CONFIG);

        String result = rewriter.rewrite("那它和钙片能一起吃吗", history());

        assertThat(result).isEqualTo("维生素D3 与钙片 能否同服");
        String prompt = llm.lastMessages.get(1).getContent();
        assertThat(prompt)
                .as("改写器看不到历史就无从消解指代")
                .contains("维生素D3 每天吃多少合适")
                .contains("那它和钙片能一起吃吗");
        assertThat(llm.lastMessages.get(0).getRole()).isEqualTo(ChatMessage.Role.SYSTEM);
    }

    @Test
    void 模型自带前缀与引号时被清洗() {
        assertThat(QueryRewriter.sanitize("改写后：「维生素D3 与钙同服」")).isEqualTo("维生素D3 与钙同服");
        assertThat(QueryRewriter.sanitize("检索语句：维生素D3 每日上限")).isEqualTo("维生素D3 每日上限");
        assertThat(QueryRewriter.sanitize("\"维生素D3 每日上限\"")).isEqualTo("维生素D3 每日上限");
        // 标签在引号里：两种清理要互相开路，只跑一遍会把「改写结果：」留在查询句里
        assertThat(QueryRewriter.sanitize("「改写结果：维生素D3 每日上限」")).isEqualTo("维生素D3 每日上限");
    }

    @Test
    void 模型输出多行时只取第一行() {
        String raw = "维生素D3 每日摄入上限\n\n说明：原句中的「它」指维生素D3。";
        assertThat(QueryRewriter.sanitize(raw)).isEqualTo("维生素D3 每日摄入上限");
    }

    @Test
    void 输出为空或超长时降级回原句() {
        assertThat(QueryRewriter.sanitize("   ")).isNull();
        assertThat(QueryRewriter.sanitize("长".repeat(201))).isNull();

        QueryRewriter blank = new QueryRewriter(new StubProvider("  "), CONFIG);
        assertThat(blank.rewrite("那它呢", history())).isEqualTo("那它呢");

        QueryRewriter tooLong = new QueryRewriter(new StubProvider("长".repeat(201)), CONFIG);
        assertThat(tooLong.rewrite("那它呢", history())).isEqualTo("那它呢");
    }

    @Test
    void 模型调用失败时降级回原句() {
        QueryRewriter rewriter = new QueryRewriter(
                new StubProvider(null, new RuntimeException("timeout")), CONFIG);

        assertThat(rewriter.rewrite("那它呢", history())).isEqualTo("那它呢");
    }

    @Test
    void Mock模型不支持推理时不调用并原样返回() {
        MockLLMProvider mock = new MockLLMProvider();
        QueryRewriter rewriter = new QueryRewriter(mock, CONFIG);

        assertThat(rewriter.rewrite("那它呢", history())).isEqualTo("那它呢");
    }

    @Test
    void 改写结果与原句相同时也原样返回() {
        QueryRewriter rewriter = new QueryRewriter(new StubProvider("维生素D3 每日上限"), CONFIG);

        assertThat(rewriter.rewrite("维生素D3 每日上限", history())).isEqualTo("维生素D3 每日上限");
    }
}
