package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.flow.FlowRegistry;
import yumefusaka.envoymart.agent.flow.IntentRouter;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.MockLLMProvider;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.memory.EpisodicMemory;
import yumefusaka.envoymart.agent.memory.ShortTermMemory;
import yumefusaka.envoymart.agent.memory.UserProfileStore;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.QueryRewriter;
import yumefusaka.envoymart.agent.rag.RAGEngine;
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立冲突核对 —— 隔离的判据与「主回答说了没有冲突才补一次」的接线。
 * <p>
 * <b>它修的是一个只在真实顺序下出现的漏报。</b>铁问句单独直发 12/12 能报出冲突，
 * 但放进「先问维生素 D、再问铁」这个真实顺序后，40 发漏了 6 发——历史里那段
 * 「核对下来并无分歧」的结论让模型不再逐条比对。这里锁两件事：
 * <ul>
 *   <li>补的那一次调用<b>输入里没有历史</b>（否则等于没隔离，漏报照旧）；</li>
 *   <li>它只在主回答<b>自己没报出冲突</b>时才发起（主回答已报出时再问一次是白花钱，
 *       而且两次结论不一致时该信哪个没有答案）。</li>
 * </ul>
 */
class AgentConflictIsolationTest {

    private static final LLMConfig LLM_CONFIG = LLMConfig.builder().model("stub").build();

    private static DocumentChunk chunk(String chunkId, String title, String content) {
        return DocumentChunk.builder()
                .chunkId(chunkId).docId("doc-" + chunkId).title(title)
                .position("《" + title + "》 > 第三章")
                .content(content)
                .build();
    }

    /** 入口检索固定给两条互相矛盾的证据：铁 20 毫克 vs 18 毫克 */
    private static final RAGEngine TWO_CONFLICTING = new RAGEngine() {
        @Override
        public void ingest(Document document) {
        }

        @Override
        public void ingestBatch(List<Document> documents) {
        }

        @Override
        public List<DocumentChunk> retrieve(String query, int topK) {
            return List.of(
                    chunk("c1", "铁叶酸片产品说明书", "成年女性每日铁推荐摄入量为 20 毫克。"),
                    chunk("c2", "中国居民膳食营养素参考摄入量速查", "铁推荐摄入量成年女性 18 毫克每日。"));
        }
    };

    /** 直接给出主回答，绕开执行图——本用例只验后置核对这一段 */
    private static class StubGraph extends AgentGraph {
        private final String answer;

        StubGraph(String answer) {
            super(new MockLLMProvider(), LLM_CONFIG, new ToolRegistry(),
                    Executors.newVirtualThreadPerTaskExecutor());
            this.answer = answer;
        }

        @Override
        public GraphResult run(String userId, String message, String systemPrompt, List<ChatMessage> conversation,
                               LoopGuard guard, Consumer<String> onChunk, ToolProgressListener progress) {
            return GraphResult.builder().answer(answer).steps(List.of()).build();
        }
    }

    /** 记录每次收到的消息，便于断言「隔离调用里没有历史」 */
    private static class RecordingChecker implements LLMProvider {
        private final String reply;
        private final List<List<ChatMessage>> calls = new ArrayList<>();

        RecordingChecker(String reply) {
            this.reply = reply;
        }

        @Override
        public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            calls.add(List.copyOf(messages));
            return LLMResponse.builder().content(reply).build();
        }
    }

    private static Agent agent(RecordingChecker checker, String answer) {
        MockLLMProvider llm = new MockLLMProvider();
        return new Agent(
                Agent.Config.builder().memoryConsolidationEnabled(false).build(),
                new ToolRegistry(),
                new IntentRouter(llm, LLM_CONFIG, new FlowRegistry()),
                new StubGraph(answer),
                new ShortTermMemory(16), new EpisodicMemory(), new UserProfileStore(),
                TWO_CONFLICTING, null, new QueryRewriter(llm, LLM_CONFIG),
                yumefusaka.envoymart.agent.core.task.TaskStateStore.NOOP, checker);
    }

    @Test
    void 主回答漏报时独立核对补出冲突() {
        RecordingChecker checker = new RecordingChecker(
                "【冲突】条目 1 与条目 2 对成年女性每日铁推荐量给出了不同的数字："
                        + "条目 1 为 20 毫克，条目 2 为 18 毫克，需人工确认以哪一份为准。");
        Agent agent = agent(checker, "成年女性每日铁推荐摄入量为 20 毫克 [1]，也可以参考 18 毫克 [2]。");

        Agent.AgentResponse response = agent.chat("u1", "s1", "成年女性每天应该摄入多少铁？", null);

        assertThat(response.getConflicts())
                .as("主回答没报出冲突时，隔离的那一次调用要把它补回来")
                .hasSize(1);
        assertThat(response.getConflicts().getFirst().refs()).containsExactly(1, 2);
        assertThat(response.getReply())
                .as("核对的产物是结论，不是对答案正文的改写——正文必须保持主回答那一版")
                .isEqualTo("成年女性每日铁推荐摄入量为 20 毫克 [1]，也可以参考 18 毫克 [2]。");
    }

    /**
     * 隔离的实质是「输入里没有历史」。带上历史就回到了漏报的老路——
     * 这条断言防的是「以后有人为了方便把 conversation 也传进去」。
     */
    @Test
    void 核对调用不带任何对话历史() {
        RecordingChecker checker = new RecordingChecker("无冲突");
        Agent agent = agent(checker, "每日推荐摄入量为 20 毫克 [1]。");

        agent.chat("u1", "s1", "维生素D每天推荐摄入多少？", null);
        agent.chat("u1", "s1", "成年女性每天应该摄入多少铁？", null);

        assertThat(checker.calls).isNotEmpty();
        for (List<ChatMessage> messages : checker.calls) {
            assertThat(messages).as("隔离调用的消息只有 system + 本次 user 两条").hasSize(2);
            assertThat(messages.get(0).getContent()).contains("资料一致性核对员");
            String user = messages.get(1).getContent();
            // 只允许出现「这一次问的那句话」，不允许出现任何更早的轮次。
            // 上面两轮分别是维D与铁，所以核对输入里可以出现当前这一轮的疑问句，
            // 但不该出现另一轮的那句——历史进到输入里，漏报就照旧发生
            int currentTurn = checker.calls.indexOf(messages);
            String expected = currentTurn == 0 ? "维生素D每天推荐摄入多少" : "成年女性每天应该摄入多少铁";
            String forbidden = currentTurn == 0 ? "成年女性每天应该摄入多少铁" : "维生素D每天推荐摄入多少";
            assertThat(user)
                    .as("核对输入只该有当前这一轮的问题，不该有其它轮次的问题")
                    .contains(expected)
                    .doesNotContain(forbidden);
        }
    }

    /** 主回答自己已经报出冲突时，不再补一次——白花钱，且两次结论不一致时没有仲裁者 */
    @Test
    void 主回答已报出冲突时不重复核对() {
        RecordingChecker checker = new RecordingChecker("无冲突");
        Agent agent = agent(checker, "成年女性每日铁推荐摄入量为 20 毫克 [1]。"
                + "\n\n【冲突】条目 1 与条目 2：条目 1 为 20 毫克，条目 2 为 18 毫克，需人工确认。");

        Agent.AgentResponse response = agent.chat("u1", "s1", "成年女性每天应该摄入多少铁？", null);

        assertThat(response.getConflicts()).hasSize(1);
        assertThat(checker.calls).as("主回答已给出结论，不必再开一次调用").isEmpty();
    }

    /**
     * 只有一条证据时连调用都不该发起：一条证据之间不可能有冲突，
     * 那一次调用是确定无意义的计费。
     */
    @Test
    void 证据不足两条时不做核对() {
        RecordingChecker checker = new RecordingChecker("无冲突");
        RAGEngine one = new RAGEngine() {
            @Override
            public void ingest(Document document) {
            }

            @Override
            public void ingestBatch(List<Document> documents) {
            }

            @Override
            public List<DocumentChunk> retrieve(String query, int topK) {
                return List.of(chunk("c1", "铁叶酸片产品说明书", "成年女性每日铁推荐摄入量为 20 毫克。"));
            }
        };
        MockLLMProvider llm = new MockLLMProvider();
        Agent agent = new Agent(
                Agent.Config.builder().memoryConsolidationEnabled(false).build(),
                new ToolRegistry(), new IntentRouter(llm, LLM_CONFIG, new FlowRegistry()),
                new StubGraph("每日推荐摄入量为 20 毫克 [1]。"),
                new ShortTermMemory(16), new EpisodicMemory(), new UserProfileStore(),
                one, null, new QueryRewriter(llm, LLM_CONFIG),
                yumefusaka.envoymart.agent.core.task.TaskStateStore.NOOP, checker);

        agent.chat("u1", "s1", "成年女性每天应该摄入多少铁？", null);

        assertThat(checker.calls).isEmpty();
    }
}
