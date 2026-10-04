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
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具检索到的切片进入本轮证据 —— 装配层的契约。
 * <p>
 * <b>断的是「最后一米」。</b>{@code knowledge_search} 中途查回来的切片原先只以《文档名》
 * 的形式活在工具输出的文本里：既不在知识依据列表里，也不占用编号。模型引用了它们，
 * 界面上却没有角标可点——用户看到一句话说是「依据」，却没有任何东西可以点开核对。
 * 这条链路的前面九十九米都是通的（切片带溯源字段、工具把结构化结果挂在 rawData 上、
 * rawData 一路带到 ToolExecution），只有出口这一米没接上。
 * <p>
 * 两件事必须同时成立，缺一条这个断口就还在：切片要进 {@code knowledge}（前端据此
 * 渲染依据列表与角标上界），正文里的《文档名》要换回那个编号（否则照样点不了）。
 */
class AgentToolEvidenceTest {

    private static final LLMConfig LLM_CONFIG = LLMConfig.builder().model("stub").build();

    private static DocumentChunk chunk(String chunkId, String title) {
        return DocumentChunk.builder()
                .chunkId(chunkId)
                .docId("doc-" + chunkId)
                .title(title)
                .position("《" + title + "》 > 第一章")
                .content("原文内容")
                .build();
    }

    /** 入口检索固定返回一条证据，对应 [1] */
    private static final RAGEngine ONE_ENTRY = new RAGEngine() {
        @Override
        public void ingest(Document document) {
        }

        @Override
        public void ingestBatch(List<Document> documents) {
        }

        @Override
        public List<DocumentChunk> retrieve(String query, int topK) {
            return List.of(chunk("c1", "维生素D3说明书"));
        }
    };

    /** 直接给出答案与工具轨迹，绕开真实执行——本用例只验出口的装配 */
    private static class StubGraph extends AgentGraph {
        private final String answer;
        private final List<ToolExecution> executions;

        StubGraph(String answer, List<ToolExecution> executions) {
            super(new MockLLMProvider(), LLM_CONFIG, new ToolRegistry(),
                    Executors.newVirtualThreadPerTaskExecutor());
            this.answer = answer;
            this.executions = executions;
        }

        @Override
        public GraphResult run(String userId, String message, String systemPrompt, List<ChatMessage> conversation,
                               LoopGuard guard, Consumer<String> onChunk, ToolProgressListener progress) {
            // steps 不能留空：Agent 用它区分 react / plan 两个出口（getSteps().isEmpty()）
            return GraphResult.builder()
                    .answer(answer).steps(List.of()).toolExecutions(executions)
                    .build();
        }
    }

    private static Agent agent(AgentGraph graph) {
        MockLLMProvider llm = new MockLLMProvider();
        return new Agent(
                Agent.Config.builder().memoryConsolidationEnabled(false).build(),
                new ToolRegistry(),
                new IntentRouter(llm, LLM_CONFIG, new FlowRegistry()),
                graph,
                new ShortTermMemory(16),
                new EpisodicMemory(),
                new UserProfileStore(),
                ONE_ENTRY,
                null,
                new QueryRewriter(llm, LLM_CONFIG));
    }

    @Test
    void 工具检索到的切片进入证据列表并占用后续编号() {
        ToolExecution search = ToolExecution.builder()
                .tool("knowledge_search").input("华法林 相互作用").success(true)
                .output("知识库检索结果（命中 1 条）\n\n[片段 1] 出处：《药物相互作用手册》 > 3.1\n与华法林同服需谨慎")
                .rawData(List.of(chunk("c2", "药物相互作用手册")))
                .build();
        Agent agent = agent(new StubGraph("每日上限为 2000IU [1]。与华法林同服需谨慎（《药物相互作用手册》）。",
                List.of(search)));

        Agent.AgentResponse response = agent.chat("u1", "s1", "维生素D3和华法林能一起吃吗", null);

        assertThat(response.getKnowledge())
                .as("工具查到的切片要进依据列表：前端据此渲染条目，也据此决定角标上界")
                .extracting(DocumentChunk::getChunkId)
                .containsExactly("c1", "c2");
        assertThat(response.getReply())
                .as("正文里的《文档名》要换回编号，否则界面上没有可点的入口")
                .contains("[2]")
                .doesNotContain("《药物相互作用手册》");
        assertThat(response.getUnsupportedClaims())
                .as("归一化之后这句话有了有效引用，不该被当成无出处的断言")
                .isNull();
    }

    /**
     * 同一个切片被入口检索和工具各命中一次是常态——模型换个说法再查一次，
     * 排在前面的还是那几条。按内容去重会连同一份文档的<b>不同</b>切片一起压掉，
     * 而多切片正是本项目的常态（47 篇 / 408 片），所以键必须是 chunkId。
     */
    @Test
    void 与入口证据重复的切片不重复计数() {
        ToolExecution search = ToolExecution.builder()
                .tool("knowledge_search").input("维生素D3 上限").success(true)
                .output("知识库检索结果（命中 1 条）")
                .rawData(List.of(chunk("c1", "维生素D3说明书")))
                .build();
        Agent agent = agent(new StubGraph("上限为 2000IU（《维生素D3说明书》）。", List.of(search)));

        Agent.AgentResponse response = agent.chat("u1", "s1", "维生素D3每天吃多少", null);

        assertThat(response.getKnowledge())
                .as("同一条切片只能占一个编号")
                .extracting(DocumentChunk::getChunkId)
                .containsExactly("c1");
        assertThat(response.getReply())
                .as("重复命中时仍然换得到编号，且指向入口那一条")
                .contains("[1]")
                .doesNotContain("《维生素D3说明书》");
    }

    /** rawData 不是切片列表的工具（订单、物流、图谱关系）不该被当成证据收进来 */
    @Test
    void 非切片形态的rawData不进入证据() {
        ToolExecution order = ToolExecution.builder()
                .tool("order_query").input("orderId=12").success(true)
                .output("订单 12 已发货").rawData("订单 12 已发货")
                .build();
        Agent agent = agent(new StubGraph("你的订单 12 已经发货了。", List.of(order)));

        Agent.AgentResponse response = agent.chat("u1", "s1", "我的订单到哪了", null);

        assertThat(response.getKnowledge())
                .as("业务系统返回的事实没有「出处」这回事，不能混进知识依据")
                .extracting(DocumentChunk::getChunkId)
                .containsExactly("c1");
    }
}
