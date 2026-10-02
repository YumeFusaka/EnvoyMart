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
 * 确认轮：<b>执行的是用户批准的那次调用，不是模型这一轮想起什么。</b>
 * <p>
 * 这一版把确认从「请求级布尔 {@code approved=true}」换成了绑定动作载荷的签名令牌。
 * 换掉的不是写法，是保证：原先用户点确认之后，服务端重新跑一遍规划，执行什么取决于
 * 模型有没有从短期记忆里把上一轮的意图回忆出来——换设备、隔久了再点、窗口滑过去，
 * 卡片还在而订单纹丝不动。现在执行的是签名里那份载荷，与对话历史无关。
 * <p>
 * 下面每个用例锁的都是这条保证的一处：载荷必须被逐字执行（不是重跑一遍），
 * 被改过的载荷必须一条都不执行，别人的令牌、过期的令牌同样一条都不执行。
 */
class AgentApprovalTokenTest {

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

    /** 记录每一次真实执行的入参 —— 「执行了没有、执行的是哪一单」全看它 */
    private static final class RecordingCancelTool implements Tool {
        private final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.builder()
                    .name("order_cancel")
                    .description("取消未支付的订单")
                    .requiresConfirmation(true)
                    .parameters(Map.of())
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call) {
            calls.add(call.getArguments());
            return ToolResult.builder()
                    .success(true)
                    .output("订单 " + call.getArguments().get("orderId") + " 已取消，库存已回补。")
                    .build();
        }
    }

    /**
     * 既当「中断出口」又当「执行图是否被碰过」的探针。
     * <p>
     * 计数是关键：确认轮如果还进图，就等于还让模型参与决定执行什么——
     * 那正是这次改造要拆掉的东西，而它从结果上看不出来（图恰好也会调同一个工具）。
     */
    private static final class PendingGraph extends AgentGraph {
        private final AtomicInteger runs = new AtomicInteger();
        private final PendingAction action;

        PendingGraph(PendingAction action) {
            super(new MockLLMProvider(), LLM_CONFIG, new ToolRegistry(),
                    Executors.newVirtualThreadPerTaskExecutor());
            this.action = action;
        }

        @Override
        public GraphResult run(String userId, String message, String systemPrompt, List<ChatMessage> conversation,
                               LoopGuard guard, Consumer<String> onChunk, ToolProgressListener progress) {
            runs.incrementAndGet();
            if (action == null) {
                return GraphResult.builder().answer("图给的回答").steps(List.of()).build();
            }
            return GraphResult.builder().pendingActions(List.of(action)).steps(List.of()).build();
        }
    }

    private static Agent agent(AgentGraph graph, ToolRegistry registry) {
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
                new QueryRewriter(llm, LLM_CONFIG));
    }

    /** 走一遍中断出口拿到令牌 —— 与前端拿到的是同一条路 */
    private static String issueToken(Agent agent, String userId, String sessionId) {
        Agent.AgentResponse interrupted = agent.chat(userId, sessionId, "帮我取消订单 12", null);
        assertThat(interrupted.getApprovalToken()).isNotBlank();
        return interrupted.getApprovalToken();
    }

    @Test
    void 确认轮按签名载荷执行并逐字回报工具结果() {
        RecordingCancelTool cancel = new RecordingCancelTool();
        PendingGraph graph = new PendingGraph(PendingAction.of("order_cancel", Map.of("orderId", 12)));
        ToolRegistry registry = new ToolRegistry();
        registry.register(cancel);
        Agent agent = agent(graph, registry);

        String token = issueToken(agent, "u1", "s1");
        Agent.AgentResponse confirmed = agent.chat("u1", "s1", "确认执行", token);

        assertThat(cancel.calls)
                .as("执行的必须是签名里那一单，一条不多一条不少")
                .containsExactly(Map.of("orderId", 12));
        assertThat(confirmed.getReply())
                .as("结果逐字来自工具：模型说「已取消」而工具其实报了错，是这条链路上唯一不能出的错")
                .contains("订单 12 已取消，库存已回补。");
        assertThat(confirmed.getPendingActions())
                .as("执行完不该再挂着待确认")
                .isNull();
    }

    @Test
    void 确认轮不进执行图() {
        RecordingCancelTool cancel = new RecordingCancelTool();
        PendingGraph graph = new PendingGraph(PendingAction.of("order_cancel", Map.of("orderId", 12)));
        ToolRegistry registry = new ToolRegistry();
        registry.register(cancel);
        Agent agent = agent(graph, registry);

        String token = issueToken(agent, "u1", "s1");
        int runsBefore = graph.runs.get();
        agent.chat("u1", "s1", "确认执行", token);

        assertThat(graph.runs.get())
                .as("进图就意味着模型重新规划一遍——用户批的是一次调用，不是一次重新决策")
                .isEqualTo(runsBefore);
        assertThat(cancel.calls).hasSize(1);
    }

    /**
     * 篡改载荷 —— 这是请求级布尔时代完全不存在的一类攻击：那时客户端只需要把布尔置位，
     * 执行什么由服务端自己重新规划；现在执行内容在客户端手里过一趟，它必须能自证没被改过。
     */
    @Test
    void 载荷被改过则一条都不执行() {
        RecordingCancelTool cancel = new RecordingCancelTool();
        PendingGraph graph = new PendingGraph(PendingAction.of("order_cancel", Map.of("orderId", 12)));
        ToolRegistry registry = new ToolRegistry();
        registry.register(cancel);
        Agent agent = agent(graph, registry);

        String token = issueToken(agent, "u1", "s1");
        // 改一个字符：base64 载荷变了，签名对不上
        char flipped = token.charAt(0) == 'A' ? 'B' : 'A';
        String tampered = flipped + token.substring(1);
        Agent.AgentResponse response = agent.chat("u1", "s1", "确认执行", tampered);

        assertThat(cancel.calls)
                .as("签名对不上就必须停在执行之前——这是令牌存在的全部意义")
                .isEmpty();
        assertThat(response.getReply())
                .as("要明确说「什么都没执行」：用户点的是不可撤销的操作，最坏的结果是他以为成功了")
                .contains("没有执行任何操作");
    }

    @Test
    void 令牌只对签发它的用户和会话有效() {
        RecordingCancelTool cancel = new RecordingCancelTool();
        PendingGraph graph = new PendingGraph(PendingAction.of("order_cancel", Map.of("orderId", 12)));
        ToolRegistry registry = new ToolRegistry();
        registry.register(cancel);
        Agent agent = agent(graph, registry);

        String token = issueToken(agent, "u1", "s1");
        // 同一个 Agent 实例（同一把密钥），换一个使用者和换一个会话分别试
        Agent.AgentResponse otherUser = agent.chat("u2", "s1", "确认执行", token);
        Agent.AgentResponse otherSession = agent.chat("u1", "s2", "确认执行", token);

        assertThat(cancel.calls)
                .as("拿到令牌的人就能替别人取消订单，那这道确认就只是转发了一次字符串")
                .isEmpty();
        assertThat(otherUser.getReply()).contains("没有执行任何操作");
        assertThat(otherSession.getReply()).contains("没有执行任何操作");
    }

    /** 没有令牌（或空令牌）就是普通的一轮，照常进图 */
    @Test
    void 不带令牌时走正常流程() {
        RecordingCancelTool cancel = new RecordingCancelTool();
        PendingGraph graph = new PendingGraph(null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(cancel);
        Agent agent = agent(graph, registry);

        int runsBefore = graph.runs.get();
        Agent.AgentResponse response = agent.chat("u1", "s1", "你好", null);

        assertThat(graph.runs.get()).isEqualTo(runsBefore + 1);
        assertThat(cancel.calls).isEmpty();
        assertThat(response.getReply()).isEqualTo("图给的回答");
    }

    /**
     * 确认轮也要发工具进度事件。
     * <p>
     * 确认轮不经执行图、直接执行签名载荷，是另一条执行路径——它漏接进度回调时
     * 不会有任何报错：用户点完「确认执行」后界面静静等几秒（取消订单要动库存、
     * 走事务），然后直接出结果，看起来只是"慢"。这一类「只有一处接线」的缺口
     * 每加一条执行路径就会出现一次，所以每条路径都钉一条。
     */
    @Test
    void 确认轮也发工具进度事件() {
        RecordingCancelTool cancel = new RecordingCancelTool();
        PendingGraph graph = new PendingGraph(PendingAction.of("order_cancel", Map.of("orderId", 12)));
        ToolRegistry registry = new ToolRegistry();
        registry.register(cancel);
        Agent agent = agent(graph, registry);
        String token = issueToken(agent, "u1", "s1");

        List<String> events = new ArrayList<>();
        ToolProgressListener listener = new ToolProgressListener() {
            @Override
            public void onStart(String tool) {
                events.add("start:" + tool);
            }

            @Override
            public void onFinish(String tool, boolean success, boolean noData, long latencyMs) {
                events.add("finish:" + tool + ":" + success);
            }
        };
        agent.chatStream("u1", "s1", "确认执行", token, chunk -> {
        }, listener);

        assertThat(events)
                .as("事件必须来自真正执行的那一次调用（载荷里的 order_cancel）")
                .containsExactly("start:order_cancel", "finish:order_cancel:true");
        assertThat(cancel.calls).hasSize(1);
    }
}
