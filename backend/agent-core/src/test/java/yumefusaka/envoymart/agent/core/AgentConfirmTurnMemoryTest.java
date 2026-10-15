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
import yumefusaka.envoymart.agent.memory.MemoryItem;
import yumefusaka.envoymart.agent.memory.ShortTermMemory;
import yumefusaka.envoymart.agent.memory.ShortTermMemoryStore;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认轮必须写进短期记忆 —— 无论从哪个入口进来。
 *
 * <p><b>现场。</b>用户「把越橘叶黄素片加入购物车」拿到确认卡，点确认、加购成功；
 * 下一轮问「我的购物车里越橘叶黄素片有几件」，模型答完数量后补了一句
 * <b>「刚才你说要把越橘叶黄素片加入购物车，这个操作还在等你确认，确认后才会执行」</b>——
 * 而它早就执行完了。用户按这句话去等一个永远不会来的确认。
 *
 * <p><b>根因是同一个语义写在两条路上。</b>确认轮有两条入口：{@code Agent.chat(…, token)}
 * 的令牌分支（它记得调 {@code remember}），与生产链路真正走的
 * {@code confirmApproved}（它没有）。于是 {@code stm:} 窗口里只有
 * 「加购 → 出卡 → 查购物车」，确认那一轮（用户说的「确认执行」+ 系统给的回执）整轮缺席——
 * 模型读到的故事是「我出了卡，用户没理我，然后问了别的」，把已执行的操作说成待确认，
 * 是它对这份残缺历史的正确反应。
 *
 * <p><b>为什么断言落在窗口内容而不是「有没有调用」。</b>调用次数对用户不可见；
 * 模型看到的就是窗口里那几行。断言那两行在不在，等于断言「下一轮模型能不能读到这件事发生过」。
 */
class AgentConfirmTurnMemoryTest {

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
            return ToolResult.builder().success(true)
                    .operationState(ToolResult.OperationState.COMMITTED)
                    .output("已加入购物车：越橘叶黄素片 × 1，小计 138.00 元。还没下单，需要结算并支付才算买到。")
                    .build();
        }
    }

    private static final class PendingGraph extends AgentGraph {
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
            return GraphResult.builder().pendingActions(actions).steps(List.of()).build();
        }
    }

    private static Agent agent(AgentGraph graph, ToolRegistry registry, ShortTermMemory shortTermMemory) {
        MockLLMProvider llm = new MockLLMProvider();
        return new Agent(
                Agent.Config.builder().memoryConsolidationEnabled(false).build(),
                registry,
                new IntentRouter(llm, LLM_CONFIG, new FlowRegistry()),
                graph,
                shortTermMemory,
                new EpisodicMemory(),
                new UserProfileStore(),
                NO_KNOWLEDGE,
                null,
                new QueryRewriter(llm, LLM_CONFIG),
                new InMemoryTaskStateStore(),
                null,
                null,
                new InMemorySessionContextStore());
    }

    @Test
    void 生产确认入口也要把确认轮写进短期记忆() {
        ShortTermMemory shortTermMemory = new ShortTermMemory(16);
        RecordingCartAdd cart = new RecordingCartAdd();
        PendingGraph graph = new PendingGraph(
                List.of(PendingAction.of("cart_add", Map.of("skuId", 28, "quantity", 1))));
        ToolRegistry registry = new ToolRegistry();
        registry.register(cart);
        Agent agent = agent(graph, registry, shortTermMemory);

        Agent.AgentResponse interrupted = agent.chat("u1", "s1", "把越橘叶黄素片加入购物车", null);
        String token = interrupted.getApprovalToken();
        assertThat(token).isNotBlank();

        // 生产链路走的是这一条（AiAssistantServiceImpl 的确认分支），不是 chat(…, token)
        Agent.AgentResponse confirmed = agent.confirmApproved("u1", "s1", "确认执行", token, null,
                ToolProgressListener.NOOP);
        assertThat(confirmed.getToolExecutions()).as("确认轮真的执行了").isNotEmpty();

        List<String> contents = shortTermMemory.recent(ShortTermMemoryStore.scoped("u1", "s1"), 20)
                .stream().map(MemoryItem::getContent).toList();

        assertThat(contents)
                .as("用户说的「确认执行」必须在窗口里：缺了它，下一轮模型读到的是"
                        + "「我出了卡 → 用户没理我 → 用户问了别的」")
                .contains("user: 确认执行");
        assertThat(contents)
                .as("系统给的回执同样必须在窗口里：它是「这件事已经办完」的唯一证据，"
                        + "缺了它模型只能把已执行的操作说成还在等确认")
                .anyMatch(content -> content.contains("已加入购物车"));
    }
}