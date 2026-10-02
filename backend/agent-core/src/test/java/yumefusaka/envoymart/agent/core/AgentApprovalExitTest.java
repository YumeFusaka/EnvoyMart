package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
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
import yumefusaka.envoymart.agent.tool.PendingAction;
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高危中断出口（Agent 层装配）：<b>回答换成确认提示，但中断之前已跑完的轨迹必须保留。</b>
 * <p>
 * 中断轮之前可能已经真跑过工具——计划路径执行过前几层（先查订单、再走到取消那一步），
 * ReAct 路径也可能查过订单才决定要取消。这些轨迹原先在审批出口被整个丢掉：
 * 用户看到的确认卡片悬在一段没有任何来路的空白上，「它为什么要取消这一单」
 * 无从对证。这条测试守的是装配本身——图给出了轨迹，AgentResponse 就必须带上。
 */
class AgentApprovalExitTest {

    private static final LLMConfig LLM_CONFIG = LLMConfig.builder().model("stub").build();

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

    /** 直接给出中断结果，绕开真实执行——本用例只验 Agent 出口的装配 */
    private static class BlockingGraph extends AgentGraph {
        private final List<ToolExecution> executions;

        BlockingGraph(List<ToolExecution> executions) {
            super(new MockLLMProvider(), LLM_CONFIG, new ToolRegistry(),
                    Executors.newVirtualThreadPerTaskExecutor());
            this.executions = executions;
        }

        @Override
        public GraphResult run(String userId, String message, String systemPrompt, List<ChatMessage> conversation,
                               LoopGuard guard, Consumer<String> onChunk, ToolProgressListener progress) {
            return GraphResult.builder()
                    .pendingActions(List.of(PendingAction.of("order_cancel", Map.of("orderId", 12))))
                    .toolExecutions(executions)
                    .build();
        }
    }

    private Agent agent(AgentGraph graph) {
        MockLLMProvider llm = new MockLLMProvider();
        return new Agent(
                Agent.Config.builder().memoryConsolidationEnabled(false).build(),
                new ToolRegistry(),
                new IntentRouter(llm, LLM_CONFIG, new FlowRegistry()),
                graph,
                new ShortTermMemory(16),
                new EpisodicMemory(),
                new UserProfileStore(),
                NO_KNOWLEDGE,
                null,
                // Mock 不支持推理，改写会自动退回原句——本用例守的是中断出口的装配
                new QueryRewriter(llm, LLM_CONFIG));
    }

    @Test
    void 中断出口给出确认卡片并保留中断前的工具轨迹() {
        ToolExecution preExecuted = ToolExecution.builder()
                .tool("order_query").input("orderId=12").output("订单 12 待支付").success(true)
                .build();
        Agent agent = agent(new BlockingGraph(List.of(preExecuted)));

        Agent.AgentResponse response = agent.chat("u1", "s1", "帮我取消订单 12", null);

        assertThat(response.getSource())
                .as("中断轮必须走 approval 出口，否则前端拿不到确认卡片")
                .isEqualTo("approval");
        assertThat(response.getPendingActions())
                .as("要确认的是哪一单必须结构化下发，前端据此渲染确认卡片")
                .containsExactly("order_cancel(orderId=12)");
        assertThat(response.getApprovalToken())
                .as("确认卡片必须一并带上签名令牌——没有它，用户点确认时前端无从"
                        + "证明自己确认的是哪一次调用，只能退回请求级布尔那条老路")
                .isNotBlank();
        assertThat(response.getToolExecutions())
                .as("中断之前跑完的查询轨迹要保留：确认卡片不能悬在没有任何来路的空白上")
                .hasSize(1)
                .first()
                .extracting(ToolExecution::getTool)
                .isEqualTo("order_query");
        assertThat(response.getReply())
                .as("回答要换成确认提示，而不是图上那个没有回答可给的空值")
                .contains("需要你确认");
    }
}
