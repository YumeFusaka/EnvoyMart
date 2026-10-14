package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.core.task.InMemorySessionContextStore;
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
import yumefusaka.envoymart.agent.rag.QueryRewriter;
import yumefusaka.envoymart.agent.rag.RAGEngine;
import yumefusaka.envoymart.agent.tool.PendingAction;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户照平台教的口令回「确认执行」，却没有可执行的载荷时的那一轮。
 * <p>
 * <b>现场。</b>{@code u1001-batch-dup-1792006692964}：用户说「把越橘叶黄素片加入购物车」拿到确认卡片，
 * 点确认后加购成功；<b>紧接着又回了一句「确认执行」</b>——系统重新生成了一张一模一样的卡片。
 * 用户再点一次，同一件商品就被加了两次，而购物车没有删减入口，他自己收拾不掉。
 * <p>
 * <b>根因是会话现场没有跟着执行结果更新。</b>确认轮在 {@code doChat} 里提前返回、走不到常规收尾，
 * 于是现场停在「执行之前」的样子：{@code pendingTools} 里始终挂着 {@code cart_add(quantity=1, skuId=28)}。
 * 下一轮 prompt 把「还有待确认的操作」照实注入，模型据此把同一件商品重新出了一张卡片——
 * 它看到的现场就是错的，重出一张是它对那份现场的正确反应。
 * <p>
 * 两处判据因此都要落在「状态」上，而不是「句式」上：
 * <ul>
 *   <li>确认轮结束后把现场的待确认操作对齐到本轮真实结果（一处判定，另一处只负责读）；</li>
 *   <li>平台自己教过「回复「确认执行」」这四个字，用户照做而又没有载荷时，由平台直接答，
 *       不再交给模型猜——那一次猜测正是重复卡片的来源。</li>
 * </ul>
 */
class AgentConfirmWithoutPayloadTest {

    private static final LLMConfig LLM_CONFIG = LLMConfig.builder().model("stub").build();

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

    /** 记录真实落地的每一次写操作 —— 「执行了没有、执行了几次」全看它 */
    private static final class RecordingCartAdd implements Tool {
        private final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.builder()
                    .name("cart_add")
                    .description("加入购物车")
                    .requiresConfirmation(true)
                    .parameters(Map.of())
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call) {
            calls.add(call.getArguments());
            // operationState 必须显式给 COMMITTED：确认轮把「没有完整权威回执」的结果
            // 一律判成 UNKNOWN，而 UNKNOWN 会让「本批是否全部落地」为否
            return ToolResult.builder().success(true)
                    .operationState(ToolResult.OperationState.COMMITTED)
                    .output("已加入购物车：越橘叶黄素片 × 1，小计 138.00 元。还没下单，需要结算并支付才算买到。")
                    .build();
        }
    }

    /**
     * 既当「中断出口」又当「这一轮有没有进过模型」的探针。
     * <p>
     * 计数是关键：平台自答的那一轮如果还进图，就等于仍然让模型参与决定「要不要再出一张卡」，
     * 而它从结果上看不出来（图恰好也会返回同一份载荷）。
     */
    private static final class PendingGraph extends AgentGraph {
        private final AtomicInteger runs = new AtomicInteger();
        private final List<PendingAction> actions;

        PendingGraph(List<PendingAction> actions) {
            super(new MockLLMProvider(), LLM_CONFIG, new ToolRegistry(),
                    Executors.newVirtualThreadPerTaskExecutor());
            this.actions = actions;
        }

        @Override
        public GraphResult run(String userId, String message, String systemPrompt, List<ChatMessage> conversation,
                               LoopGuard guard, Consumer<String> onChunk, ToolProgressListener progress,
                               java.util.function.Supplier<RetrievalResult> retriever) {
            runs.incrementAndGet();
            return GraphResult.builder().pendingActions(actions).steps(List.of()).build();
        }
    }

    private static Agent agent(AgentGraph graph, ToolRegistry registry, InMemorySessionContextStore contexts) {
        MockLLMProvider llm = new MockLLMProvider();
        return new Agent(
                Agent.Config.builder().memoryConsolidationEnabled(false).build(),
                registry,
                new IntentRouter(llm, LLM_CONFIG, new FlowRegistry()),
                graph,
                new ShortTermMemory(16),
                new EpisodicMemory(),
                new UserProfileStore(),
                NO_KNOWLEDGE,
                null,
                new QueryRewriter(llm, LLM_CONFIG),
                new InMemoryTaskStateStore(),
                null,
                null,
                contexts);
    }

    @Test
    void 执行完再回一句确认口令不再长出第二张卡片() {
        RecordingCartAdd cart = new RecordingCartAdd();
        PendingGraph graph = new PendingGraph(
                List.of(PendingAction.of("cart_add", Map.of("skuId", 28, "quantity", 1))));
        ToolRegistry registry = new ToolRegistry();
        registry.register(cart);
        InMemorySessionContextStore contexts = new InMemorySessionContextStore();
        Agent agent = agent(graph, registry, contexts);

        Agent.AgentResponse interrupted = agent.chat("u1", "s1", "把越橘叶黄素片加入购物车", null);
        assertThat(interrupted.getApprovalToken()).isNotBlank();
        assertThat(contexts.load("u1", "s1").orElseThrow().pendingTools())
                .as("中断轮把待确认操作写进现场 —— 这是下面那条断言的前提，也是模型读到的东西")
                .containsExactly("cart_add(quantity=1, skuId=28)");

        Agent.AgentResponse confirmed = agent.chat("u1", "s1", "确认执行", interrupted.getApprovalToken());
        assertThat(cart.calls).as("带令牌的确认照常执行").hasSize(1);
        assertThat(contexts.load("u1", "s1").orElseThrow().pendingTools())
                .as("执行完的现场不该还写着「还有待确认的操作」：下一轮 prompt 会照实注入它，"
                        + "模型据此把同一件商品重新出一张卡")
                .isEmpty();

        int runsBefore = graph.runs.get();
        Agent.AgentResponse again = agent.chat("u1", "s1", "确认执行", null);

        assertThat(again.getPendingActions())
                .as("没有载荷就没有可确认的目标，不能再生成一张卡片")
                .isNullOrEmpty();
        assertThat(again.getApprovalToken()).isNull();
        assertThat(graph.runs.get())
                .as("这一轮由平台自己答，不该进执行图（也就不该花一次模型调用）")
                .isEqualTo(runsBefore);
        assertThat(cart.calls)
                .as("第二次确认不该产生第二次副作用 —— 用户再点一次就是同一件商品加两遍")
                .hasSize(1);
        assertThat(again.getReply()).contains("没有执行任何操作");
        assertThat(Agent.PLATFORM_AUTHORED_SOURCES).contains(again.getSource());
    }

    @Test
    void 卡片还挂着时不带令牌的确认口令仍走正常链路() {
        RecordingCartAdd cart = new RecordingCartAdd();
        PendingGraph graph = new PendingGraph(
                List.of(PendingAction.of("cart_add", Map.of("skuId", 28, "quantity", 1))));
        ToolRegistry registry = new ToolRegistry();
        registry.register(cart);
        InMemorySessionContextStore contexts = new InMemorySessionContextStore();
        Agent agent = agent(graph, registry, contexts);

        agent.chat("u1", "s1", "把越橘叶黄素片加入购物车", null);
        int runsBefore = graph.runs.get();
        Agent.AgentResponse response = agent.chat("u1", "s1", "确认执行", null);

        assertThat(graph.runs.get())
                .as("现场还挂着一个没执行的操作时，这句话不是「没有载荷」，仍然交给模型接着上一轮的语境说")
                .isEqualTo(runsBefore + 1);
        assertThat(cart.calls)
                .as("没有令牌就没有执行授权：进图也不等于执行，副作用一次都不能落地")
                .isEmpty();
        assertThat(response.getPendingActions()).isNotEmpty();
    }

    @Test
    void 口令混在句子里时仍然交给模型理解() {
        RecordingCartAdd cart = new RecordingCartAdd();
        PendingGraph graph = new PendingGraph(List.of());
        ToolRegistry registry = new ToolRegistry();
        registry.register(cart);
        InMemorySessionContextStore contexts = new InMemorySessionContextStore();
        Agent agent = agent(graph, registry, contexts);

        int runsBefore = graph.runs.get();
        agent.chat("u1", "s1", "帮我确认执行一下退款流程", null);

        assertThat(graph.runs.get())
                .as("判据是「整句就是平台教的那四个字」；带上下文的话里混着业务意图，该由模型理解")
                .isEqualTo(runsBefore + 1);
    }
}