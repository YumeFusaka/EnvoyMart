package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.flow.FlowRegistry;
import yumefusaka.envoymart.agent.flow.IntentRouter;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.MockLLMProvider;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.memory.EpisodicMemory;
import yumefusaka.envoymart.agent.memory.MemoryItem;
import yumefusaka.envoymart.agent.memory.ProfileEntry;
import yumefusaka.envoymart.agent.memory.ProfileSlot;
import yumefusaka.envoymart.agent.memory.ShortTermMemory;
import yumefusaka.envoymart.agent.memory.UserProfileStore;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.QueryRewriter;
import yumefusaka.envoymart.agent.rag.RAGEngine;
import yumefusaka.envoymart.agent.rag.VectorStore;
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨会话记忆的来源必须标注 —— 发现 N（N1 文档 §2.13）。
 *
 * <p><b>实测：</b>模型把画像里读出的「你 200 元以内的预算」说成「你刚才说」，
 * 而该会话里用户从没提过预算（`profile:u1003` 里才有）。情节记忆同样会被
 * 说成「你之前提到在补钙」——用户当轮没说过，却被追问一个他没给过的前提。
 *
 * <p>日期字段已经是「更早」的证据，但模型不会自己推——口径必须写死在提示词里，
 * 所以这条门禁只能断言提示词文本本身。
 */
class AgentMemoryProvenancePromptTest {

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

    /** 只做归属记录与线性返回的桩向量库，不涉及真实向量计算 */
    private static final class StubVectorStore implements VectorStore {
        private final List<DocumentChunk> chunks = new java.util.ArrayList<>();

        @Override
        public void indexBatch(List<DocumentChunk> batch) {
            chunks.addAll(batch);
        }

        @Override
        public List<DocumentChunk> search(String query, int topK) {
            return chunks.stream().limit(topK).toList();
        }

        @Override
        public void deleteByDocId(String docId) {
            chunks.removeIf(c -> docId.equals(c.getDocId()));
        }

        @Override
        public void deleteByIds(List<String> chunkIds) {
            chunks.removeIf(c -> chunkIds.contains(c.getChunkId()));
        }

        @Override
        public void removeAll() {
            chunks.clear();
        }
    }

    /** 拦下图收到的系统提示词 —— 它才是真正送到模型面前的东西 */
    private static final class CapturingGraph extends AgentGraph {
        volatile String lastSystemPrompt = "";

        CapturingGraph() {
            super(new MockLLMProvider(), LLMConfig.builder().model("stub").build(), new ToolRegistry(),
                    Executors.newVirtualThreadPerTaskExecutor());
        }

        @Override
        public GraphResult run(String userId, String message, String systemPrompt, List<ChatMessage> conversation,
                               LoopGuard guard, Consumer<String> onChunk, ToolProgressListener progress,
                               java.util.function.Supplier<RetrievalResult> retriever) {
            this.lastSystemPrompt = systemPrompt == null ? "" : systemPrompt;
            return GraphResult.builder().answer("stub").steps(List.of()).build();
        }
    }

    /**
     * 带画像与情节记忆的提示词。
     *
     * <p>画像直接写进 store（跨会话累积的形态），情节记忆经向量库召回
     * —— 两条注入路径都要真实走一遍，只测 {@code buildSystemPrompt} 是测不到
     * 「召回为空时整段不注入」这类边界的。
     */
    private String promptWithMemory() {
        MockLLMProvider llm = new MockLLMProvider();
        LLMConfig llmConfig = LLMConfig.builder().model("stub").build();

        UserProfileStore profileStore = new UserProfileStore();
        profileStore.update("u1003", List.of(
                ProfileEntry.builder().slot(ProfileSlot.BUDGET).value("200 元以内").confidence(0.9).build()));

        EpisodicMemory episodic = new EpisodicMemory(new StubVectorStore());
        episodic.add(MemoryItem.builder().id("m1").userId("u1003").sessionId("s-old")
                .content("用户提到自己在补钙").type(MemoryItem.Type.FACT).build());

        CapturingGraph graph = new CapturingGraph();
        Agent capturing = new Agent(
                Agent.Config.builder().memoryConsolidationEnabled(false).build(),
                new ToolRegistry(),
                new IntentRouter(llm, llmConfig, new FlowRegistry()),
                graph,
                new ShortTermMemory(16),
                episodic,
                profileStore,
                NO_KNOWLEDGE,
                null,
                new QueryRewriter(llm, llmConfig));
        capturing.chat("u1003", "s-new", "有什么推荐的", null);
        return graph.lastSystemPrompt;
    }

    @Test
    void 用户画像必须标注为跨会话来源而非本轮发言() {
        String prompt = promptWithMemory();

        assertThat(prompt)
                .as("画像的内容源自在更早的会话里累积的偏好；不标注来源，模型会说成「你刚才说」"
                        + "——而用户当轮根本没提过这个前提")
                .contains("## 用户画像")
                .contains("不是用户本轮说过的话");
        assertThat(prompt)
                .as("只写「更早」不够：必须把错误的说法本身列为禁止，否则模型仍会照旧措辞")
                .contains("不要用「你刚才说」");
    }

    @Test
    void 相关记忆必须标注为更早会话的记录() {
        String prompt = promptWithMemory();

        assertThat(prompt)
                .as("情节记忆按语义召回，来源同样是更早的会话；日期字段是证据但不是指令，"
                        + "模型不会自己把它翻译成措辞约束")
                .contains("## 相关记忆")
                .contains("不是用户本轮说过的话");
        assertThat(prompt)
                .as("给正例说法，不能只给禁令——否则模型只会换个说法继续误述")
                .contains("按之前记录的");
    }
}