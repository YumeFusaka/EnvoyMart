package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.flow.FlowRegistry;
import yumefusaka.envoymart.agent.flow.IntentRouter;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.MockLLMProvider;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.memory.EpisodicMemory;
import yumefusaka.envoymart.agent.memory.ShortTermMemory;
import yumefusaka.envoymart.agent.memory.UserProfileStore;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.RAGEngine;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话窗口必须按用户隔离 —— 安全回归防线。
 * <p>
 * sessionId 由客户端提供（前端生成的是时间戳，可枚举）。短期记忆以前只按 sessionId 存，
 * 于是**任何人拿到或猜中别人的 sessionId 就能读到那段对话**——而这段对话会被原样注入 prompt。
 * 实测确认过：同一个 sessionId 换成另一个 userId 提问，模型能复述出前一个用户说过的话。
 * <p>
 * <b>断言的是「图收到的对话内容」，不是「存储用了什么 key」。</b>
 * 一开始我把断言写在内部 key 上（`userId + "|" + sessionId`），结果它测的是 key 的拼法而不是隔离本身：
 * 去掉隔离后所有用例一起失败，失败原因却是"读不到"，真正该验的那条断言空转了。
 * 改成断言模型能看到什么，才是这条安全属性本身。
 */
class AgentSessionIsolationTest {

    /** 只做角色转换，不参与本用例的断言 */
    private static final RAGEngine NO_KNOWLEDGE = new RAGEngine() {
        @Override
        public void ingest(Document document) {
        }

        @Override
        public void ingestBatch(List<Document> documents) {
        }

        @Override
        public List<DocumentChunk> retrieve(String query, int topK) {
            return List.of();
        }
    };

    /** 拦下图收到的对话历史 —— 这才是会被注入 prompt 的东西 */
    private static class CapturingGraph extends AgentGraph {
        volatile List<ChatMessage> lastConversation = List.of();

        CapturingGraph(LLMConfig llmConfig) {
            super(new MockLLMProvider(), llmConfig, new ToolRegistry(),
                    Executors.newVirtualThreadPerTaskExecutor());
        }

        @Override
        public GraphResult run(String userId, String message, String systemPrompt, List<ChatMessage> conversation,
                               boolean approved, LoopGuard guard, Consumer<String> onChunk) {
            this.lastConversation = conversation == null ? List.of() : conversation;
            return GraphResult.builder().answer("stub").steps(List.of()).build();
        }
    }

    private final LLMConfig llmConfig = LLMConfig.builder().model("stub").build();
    private final CapturingGraph graph = new CapturingGraph(llmConfig);

    private Agent agent() {
        MockLLMProvider llm = new MockLLMProvider();
        ToolRegistry tools = new ToolRegistry();
        return new Agent(
                Agent.Config.builder().memoryConsolidationEnabled(false).build(),
                tools,
                new IntentRouter(llm, llmConfig, new FlowRegistry()),
                graph,
                new ShortTermMemory(16),
                new EpisodicMemory(),
                new UserProfileStore(),
                NO_KNOWLEDGE,
                null);
    }

    private List<String> seenByModel() {
        return graph.lastConversation.stream().map(ChatMessage::getContent).toList();
    }

    @Test
    void 不同用户使用同一sessionId时读不到彼此的对话() {
        Agent agent = agent();

        agent.chat("u1001", "shared-session", "我最喜欢的颜色是红色", false);
        agent.chat("u1002", "shared-session", "你好", false);

        assertThat(seenByModel())
                .as("u1002 用同一个 sessionId 提问时，模型看不到 u1001 说过的话")
                .noneMatch(c -> c.contains("红色"));
    }

    @Test
    void 同一用户同一会话的上下文照常累积() {
        Agent agent = agent();

        agent.chat("u1001", "s1", "我最喜欢的颜色是红色", false);
        agent.chat("u1001", "s1", "那蓝色呢", false);

        assertThat(seenByModel())
                .as("加用户维度只是加隔离，同一用户同一会话的上下文必须照常带到模型面前")
                .anyMatch(c -> c.contains("红色"));
    }

    @Test
    void 同一用户的不同会话互相独立() {
        Agent agent = agent();

        agent.chat("u1001", "s-a", "甲会话的内容", false);
        agent.chat("u1001", "s-b", "乙会话的内容", false);

        assertThat(seenByModel())
                .as("换会话就不该再看到上一个会话的内容")
                .noneMatch(c -> c.contains("甲会话"));
    }
}
