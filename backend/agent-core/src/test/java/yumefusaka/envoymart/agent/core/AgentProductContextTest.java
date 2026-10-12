package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.core.task.InMemorySessionContextStore;
import yumefusaka.envoymart.agent.flow.FlowRegistry;
import yumefusaka.envoymart.agent.flow.IntentRouter;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.MockLLMProvider;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.memory.EpisodicMemory;
import yumefusaka.envoymart.agent.memory.ShortTermMemory;
import yumefusaka.envoymart.agent.memory.UserProfileStore;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.QueryRewriter;
import yumefusaka.envoymart.agent.rag.RAGEngine;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.agent.tool.ProductCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** 原始完整候选与后续单品操作不能混成同一份列表，确定性指代必须抵达规划器。 */
class AgentProductContextTest {
    private static final LLMConfig CONFIG = LLMConfig.builder().model("stub").build();
    private static final RAGEngine NO_KNOWLEDGE = new RAGEngine() {
        @Override public void ingest(Document document) { }
        @Override public void ingestBatch(List<Document> documents) { }
        @Override public List<DocumentChunk> retrieve(String query, int topK) { return List.of(); }
    };

    @Test
    void 候选事实包含完整后项且确定性消解传入真实规划消息() {
        var graph = new CapturingGraph();
        var store = new InMemorySessionContextStore();
        store.save(yumefusaka.envoymart.agent.core.task.SessionContext.empty("u1", "s1", 0)
                .switchTo("搜索蛋白粉", 1).advance("候选列表", Map.of("lastCandidates",
                        List.of("乳清蛋白粉", "植物蛋白粉", "胶原蛋白肽粉")), 1));
        var agent = agent(graph, store);

        agent.chat("u1", "s1", "将原列表第2个商品加购1件，等我确认", null);

        assertThat(graph.messages.get(0)).contains("植物蛋白粉");
        assertThat(graph.prompts.get(0)).contains("完整候选列表", "植物蛋白粉", "胶原蛋白肽粉");
    }

    @Test
    void 单品选择回执不覆盖原列表() {
        var graph = new CapturingGraph();
        graph.reply = "**1. 植物蛋白粉** 规格已核对，等你确认";
        var store = new InMemorySessionContextStore();
        var initial = List.of("乳清蛋白粉", "植物蛋白粉", "胶原蛋白肽粉");
        store.save(yumefusaka.envoymart.agent.core.task.SessionContext.empty("u1", "s1", 0)
                .switchTo("搜索蛋白粉", 1).advance("候选列表", Map.of("lastCandidates", initial), 1));
        var agent = agent(graph, store);

        agent.chat("u1", "s1", "第2个加购1件", null);
        agent.chat("u1", "s1", "再将原列表第3个加购1件", null);

        assertThat(graph.messages.get(1)).contains("胶原蛋白肽粉");
        assertThat(store.load("u1", "s1").orElseThrow().contextSnapshot().get("lastCandidates"))
                .isEqualTo(initial);
    }

    @Test
    void 明确重新搜索产生新列表替换旧序号() {
        var graph = new CapturingGraph();
        graph.reply = "**1. 鱼油软胶囊**\n**2. 深海鱼油胶丸**";
        graph.products = List.of(new Candidate("SPU7", "鱼油软胶囊", List.of("100粒")),
                new Candidate("SPU33", "深海鱼油胶丸", List.of("60粒")));
        var store = new InMemorySessionContextStore();
        store.save(yumefusaka.envoymart.agent.core.task.SessionContext.empty("u1", "s1", 0)
                .switchTo("搜索蛋白粉", 1).advance("候选列表", Map.of("lastCandidates",
                        List.of("乳清蛋白粉", "植物蛋白粉")), 1));
        var agent = agent(graph, store);

        agent.chat("u1", "s1", "重新搜索鱼油，展示候选", null);
        agent.chat("u1", "s1", "原列表第2个加购1件", null);

        assertThat(graph.messages.get(1)).contains("深海鱼油胶丸").doesNotContain("植物蛋白粉");
    }

    @Test
    void 最终候选表与结构化快照同序且不依赖模型表格圈号() {
        var graph = new CapturingGraph();
        graph.reply = "| ① | 孕妇钙片 |\n| ② | 氨糖维 D 钙片（中老年） |";
        graph.products = List.of(new Candidate("SPU25", "孕妇钙片", List.of("60片（SKU34）")),
                new Candidate("SPU39", "氨糖维 D 钙片（中老年）", List.of("未标注规格文字（SKU50）")));
        var store = new InMemorySessionContextStore();
        var agent = agent(graph, store);

        var first = agent.chat("u1", "s1", "搜索钙片并展示候选", null);
        agent.chat("u1", "s1", "原列表第二个加购1件，等我确认", null);

        assertThat(first.getProductCandidates()).extracting(item -> item.key()).containsExactly("SPU25", "SPU39");
        assertThat(first.getReply()).containsSubsequence("1. 孕妇钙片（SPU25）", "2. 氨糖维 D 钙片（中老年）（SPU39）");
        assertThat(graph.messages.get(1)).contains("氨糖维 D 钙片（中老年）", "商品编号 SPU39");
        assertThat(graph.prompts.get(1)).contains("SKU50", "本轮查询结果下标");
    }

    @Test
    void 单品执行查询不改列表且跨话题回指仍保留原候选() {
        var graph = new CapturingGraph();
        graph.products = List.of(new Candidate("SPU3", "乳清蛋白粉", List.of("香草1kg")),
                new Candidate("SPU4", "植物蛋白粉", List.of("单一规格")));
        var store = new InMemorySessionContextStore();
        var agent = agent(graph, store);
        agent.chat("u1", "s1", "搜索蛋白粉", null);
        graph.products = List.of(new Candidate("SPU3", "乳清蛋白粉", List.of("香草1kg")));
        agent.chat("u1", "s1", "原列表第1个加购1件", null);
        graph.products = List.of();
        agent.chat("u1", "s1", "查询订单状态", null);
        agent.chat("u1", "s1", "原列表第2个加购1件", null);

        assertThat(graph.messages.get(3)).contains("植物蛋白粉", "SPU4");
        assertThat(graph.prompts.get(2)).doesNotContain("## 完整候选列表");
        assertThat(graph.prompts.get(3)).contains("## 完整候选列表", "SPU4");
    }

    @Test
    void 新搜索空结果清空旧候选不继续用旧序号() {
        var graph = new CapturingGraph();
        graph.products = List.of(new Candidate("SPU3", "乳清蛋白粉", List.of("香草1kg")));
        var agent = agent(graph, new InMemorySessionContextStore());
        agent.chat("u1", "s1", "搜索蛋白粉", null);
        graph.products = List.of();
        agent.chat("u1", "s1", "重新搜索不存在的商品", null);
        agent.chat("u1", "s1", "第一个加购1件", null);

        assertThat(graph.messages.get(2)).doesNotContain("乳清蛋白粉");
    }

    @Test
    void 工具纪律覆盖加购与等待确认并允许必要的澄清() {
        var graph = new CapturingGraph();
        var agent = agent(graph, new InMemorySessionContextStore());

        agent.chat("u1", "s1", "乳清蛋白粉香草1kg加购1件，等我确认", null);

        assertThat(graph.prompts.get(0)).contains("加购", "等我确认", "待审批", "必须由用户提供");
    }

    @Test
    void 流式商品候选只发布最终权威序号不先泄漏模型旧编号() {
        var graph = new CapturingGraph();
        graph.reply = "3. **鱼油**\n1. **维生素C**\n有症状持续请就医。";
        graph.products = List.of(new Candidate("SPU29", "维生素C", List.of("60片（SKU38）")),
                new Candidate("SPU7", "鱼油", List.of("100粒（SKU12）")));
        var agent = agent(graph, new InMemorySessionContextStore());
        var chunks = new StringBuilder();

        var result = agent.chatStream("u1", "s1", "搜索维生素C和鱼油", null, chunks::append, null);

        assertThat(chunks.toString()).isEqualTo(result.getReply())
                .doesNotContain("3. **鱼油**", "1. **维生素C**")
                .containsSubsequence("1. 维生素C", "2. 鱼油").contains("有症状持续请就医。");
    }

    private static Agent agent(CapturingGraph graph, InMemorySessionContextStore store) {
        var tools = new ToolRegistry();
        tools.register(new Tool() {
            @Override public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("cart_add").requiresConfirmation(true).description("加购").build();
            }
            @Override public ToolResult execute(ToolCall call) {
                throw new AssertionError("捕获上下文测试不得写入");
            }
        });
        var provider = new MockLLMProvider();
        return new Agent(Agent.Config.builder().memoryConsolidationEnabled(false).build(), tools,
                new IntentRouter(provider, CONFIG, new FlowRegistry()), graph, new ShortTermMemory(16),
                new EpisodicMemory(), new UserProfileStore(), NO_KNOWLEDGE, null,
                new QueryRewriter(provider, CONFIG), null, null, null, store);
    }

    private static final class CapturingGraph extends AgentGraph {
        private final List<String> messages = new ArrayList<>();
        private final List<String> prompts = new ArrayList<>();
        private String reply = "已接收本轮选择";
        private List<Candidate> products = List.of();

        private CapturingGraph() {
            super(new MockLLMProvider(), CONFIG, new ToolRegistry(), Executors.newVirtualThreadPerTaskExecutor());
        }

        @Override public GraphResult run(String userId, String message, String systemPrompt,
                List<ChatMessage> conversation, LoopGuard guard, Consumer<String> onChunk,
                ToolProgressListener progress, java.util.function.Supplier<RetrievalResult> retriever) {
            messages.add(message);
            prompts.add(systemPrompt);
            if (onChunk != null) onChunk.accept(reply);
            return GraphResult.builder().answer(reply).plan(List.of()).steps(List.of())
                    .toolExecutions(products.isEmpty()
                            && !yumefusaka.envoymart.agent.core.task.ProductCandidateList.requestsNewList(message)
                            ? List.of() : List.of(ToolExecution.builder().tool("product_search")
                            .success(true).output("商品查询").rawData(products).build()))
                    .stage(TaskStage.DONE).build();
        }
    }

    public record Candidate(String candidateKey, String candidateName, List<String> candidateSpecs)
            implements ProductCandidate { }
}
