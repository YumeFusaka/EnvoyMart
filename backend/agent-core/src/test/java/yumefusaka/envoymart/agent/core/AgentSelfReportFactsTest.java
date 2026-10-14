package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.llm.PlanWithIntent;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.EvidenceGate;
import yumefusaka.envoymart.agent.rag.KnowledgePrompt;
import yumefusaka.envoymart.agent.rag.RetrievalOutcome;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关于「系统自身行为」的陈述，必须由系统事实回答。
 * <p>
 * 症状是模型对自己的检索史没有知情权：prompt 里没有知识段时，它分不清
 * 「没查」与「查了没命中」，于是用最像话的那一种补上——实测出现过
 * 「我先按平台知识库查了相关依据，没有查到足够明确的说明」，而那一轮
 * {@code toolCalls=[]} 且 {@code knowledge=0}，一次检索都没发生过。
 * <p>
 * 这里锁的是三件事，缺一不可：
 * <ul>
 *   <li><b>事实本身</b>——本轮有没有检索、命中几条、调用了哪些工具、各自成功还是失败；</li>
 *   <li><b>两条路径都注入</b>——只注入有工具结果的那条，另一条仍会凭空自述；</li>
 *   <li><b>权威声明的措辞</b>——只给事实而不说它权威，模型仍会拿记忆覆盖它。</li>
 * </ul>
 * 三种检索形态（没查 / 查了命中 N 条 / 查了但失败）必须分开陈述：合并任意两种，
 * 都会让模型把一种事实说成另一种，而这两种说法对用户的意义完全不同。
 */
class AgentSelfReportFactsTest {

    private static final LLMConfig CONFIG = LLMConfig.builder().model("stub").build();

    /** 记录每一次模型调用收到的完整消息列表 —— prompt 真正的内容只有这里看得见 */
    private static final class CapturingProvider implements LLMProvider {
        private final List<PlanStep> plan;
        private final boolean needRetrieval;
        private final List<List<ChatMessage>> calls = Collections.synchronizedList(new ArrayList<>());

        CapturingProvider(List<PlanStep> plan, boolean needRetrieval) {
            this.plan = plan;
            this.needRetrieval = needRetrieval;
        }

        @Override
        public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            calls.add(List.copyOf(messages));
            return LLMResponse.builder().content("作答").build();
        }

        @Override
        public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                         Map<String, Object> toolContext) {
            calls.add(List.copyOf(messages));
            return LLMResponse.builder().content("作答").build();
        }

        @Override
        public PlanWithIntent planWithIntent(String message, List<ToolDefinition> tools, String context) {
            return new PlanWithIntent(plan, needRetrieval);
        }

        /** 重规划一律返回空：每个用例只该看到一轮执行 */
        @Override
        public List<PlanStep> plan(String message, List<ToolDefinition> tools, String context) {
            return List.of();
        }

        /** 收口回答那一次调用看到的全部文本 */
        String answerPrompt() {
            List<List<ChatMessage>> snapshot = List.copyOf(calls);
            assertThat(snapshot).as("收口回答必须真的发起过模型调用").isNotEmpty();
            return snapshot.get(snapshot.size() - 1).stream()
                    .map(ChatMessage::getContent)
                    .collect(Collectors.joining("\n"));
        }
    }

    private static Tool tool(String name, ToolResult result) {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name(name).description("测试工具")
                        .parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return result;
            }
        };
    }

    // ==================== 直接锁渲染 ====================

    @Test
    void 三种检索形态必须分别陈述() {
        assertThat(AgentGraph.renderExecutionFacts(AgentGraph.RetrievalFact.notAttempted(), List.of()))
                .as("没查：必须说清「不是查了没有，是没有查」——这两种说法对用户意义完全不同")
                .contains("未做知识库检索")
                .contains("不是「查了没有」，是「没有查」")
                .doesNotContain("已检索");

        assertThat(AgentGraph.renderExecutionFacts(AgentGraph.RetrievalFact.attempted(3), List.of()))
                .as("查了且命中：条数要如实给出，它是「手上有没有依据」的判断依据")
                .contains("已检索，命中 3 条条目")
                .contains("能否作为结论依据，以上文「知识依据」段的规定为准")
                .doesNotContain("未做知识库检索");

        assertThat(AgentGraph.renderExecutionFacts(AgentGraph.RetrievalFact.attempted(0), List.of()))
                .as("查了但没命中：不能说成「没查」，也不能说成「有依据」")
                .contains("已检索，未命中任何条目")
                .doesNotContain("未做知识库检索");

        assertThat(AgentGraph.renderExecutionFacts(AgentGraph.RetrievalFact.broken(), List.of()))
                .as("检索失败：既不是「没查」，也不是「平台没有」——它是没查成")
                .contains("检索未成功")
                .doesNotContain("未做知识库检索");
    }

    @Test
    void 工具轨迹按成功失败逐条陈述且无工具时明说未调用() {
        List<AgentGraph.GraphStep> steps = List.of(
                AgentGraph.GraphStep.builder().tool("product_search").success(true).build(),
                AgentGraph.GraphStep.builder().tool("order_query").success(false).build(),
                AgentGraph.GraphStep.builder().tool("cart_query").success(true).noData(true).build());

        assertThat(AgentGraph.renderExecutionFacts(AgentGraph.RetrievalFact.notAttempted(), steps))
                .as("工具名与结局都要在：模型据此回答「我查了什么」")
                .contains("product_search：成功")
                .contains("order_query：失败")
                .contains("cart_query：无结果")
                .contains("共 3 次");
        assertThat(AgentGraph.renderExecutionFacts(AgentGraph.RetrievalFact.notAttempted(), steps))
                .as("「无结果」必须与「失败」可区分，否则模型会把一次成功的空查询说成调用失败")
                .contains("无结果（调用成功，但没有查到数据）");

        assertThat(AgentGraph.renderExecutionFacts(AgentGraph.RetrievalFact.notAttempted(), List.of()))
                .as("一次工具都没调时也要明说，否则「没调用」与「没记录」长得一样")
                .contains("未调用任何工具");
    }

    @Test
    void 事实段必须声明自身权威且只给事实不给结论() {
        String facts = AgentGraph.renderExecutionFacts(AgentGraph.RetrievalFact.attempted(1), List.of());

        assertThat(facts)
                .as("只给事实而不声明它权威，模型会拿自己的记忆覆盖它")
                .contains("权威")
                .contains("不得凭记忆否认或补充")
                .contains("不得声称做过这里没有记录的动作");
        assertThat(facts)
                .as("只注入事实，不注入结论：写法一旦变成「所以你可以说」，"
                        + "它就从一份可核对的事实退化成了给模型的话术模板")
                .doesNotContain("所以你可以说")
                .doesNotContain("你可以声称");
    }

    // ==================== 两条路径都要注入 ====================

    @Test
    void 无工具路径也要注入执行事实() {
        CapturingProvider provider = new CapturingProvider(List.of(), false);
        String prompt = runGraph(provider, new ToolRegistry(), () -> AgentGraph.RetrievalResult.empty());

        assertThat(prompt)
                .as("纯对话路径同样会产生「我查过/没查过」这类自述，"
                        + "只注入有工具结果的那条等于让这条继续凭空自述")
                .contains("本轮执行事实（权威）")
                .contains("未做知识库检索")
                .contains("未调用任何工具")
                .contains("不得凭记忆否认或补充");
    }

    @Test
    void 有工具结果路径注入工具名与结局() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("product_search",
                ToolResult.builder().success(true).output("找到 2 个商品").build()));
        CapturingProvider provider = new CapturingProvider(
                List.of(PlanStep.builder().tool("product_search").arguments(Map.of("query", "维生素C"))
                        .reason("查商品").build()), false);

        String prompt = runGraph(provider, registry, () -> AgentGraph.RetrievalResult.empty());

        assertThat(prompt)
                .contains("本轮执行事实（权威）")
                .contains("product_search：成功")
                .contains("共 1 次");
    }

    @Test
    void 工具失败时事实段如实记失败() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("order_query",
                ToolResult.builder().success(false).errorMessage("订单不存在").build()));
        CapturingProvider provider = new CapturingProvider(
                List.of(PlanStep.builder().tool("order_query").arguments(Map.of("orderId", 22))
                        .reason("查订单").build()), false);

        String prompt = runGraph(provider, registry, () -> AgentGraph.RetrievalResult.empty());

        assertThat(prompt)
                .as("失败也必须如实记录：模型不能把一次失败的调用说成成功")
                .contains("order_query：失败")
                .doesNotContain("order_query：成功");
    }

    @Test
    void 检索命中条数来自真实知识段而非固定值() {
        List<DocumentChunk> chunks = List.of(
                chunk("d1", "《维生素 D3 软胶囊说明书》 > 第二章 > 3.2", 0.78),
                chunk("d2", "《维生素 D3 软胶囊说明书》 > 第三章 > 4.1", 0.72));
        KnowledgePrompt.Section section =
                KnowledgePrompt.render(chunks, EvidenceGate.evaluate(chunks, EvidenceGate.Thresholds.defaults()));

        CapturingProvider provider = new CapturingProvider(List.of(), true);
        String prompt = runGraph(provider, new ToolRegistry(),
                () -> new AgentGraph.RetrievalResult(section.text(), RetrievalOutcome.textOnly(chunks)));

        assertThat(prompt)
                .as("条数必须来自本轮真实渲染出的知识段：写死一个数字等于换了个地方编事实")
                .contains("已检索，命中 2 条条目")
                .doesNotContain("未做知识库检索");
    }

    // ==================== 夹具 ====================

    private String runGraph(CapturingProvider provider, ToolRegistry registry,
                            java.util.function.Supplier<AgentGraph.RetrievalResult> retriever) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentGraph graph = new AgentGraph(provider, CONFIG, registry, executor);
            graph.run("u1", "s1", "问题", "系统提示", List.of(), null, null, null, retriever, List.of());
        }
        return provider.answerPrompt();
    }

    private static DocumentChunk chunk(String id, String position, double score) {
        return DocumentChunk.builder()
                .chunkId(id + "_0").docId(id)
                .title("维生素 D3 软胶囊说明书")
                .position(position)
                .source("manual").version("v2026.03")
                .scope("nutrition")
                .content("每日推荐摄入量为 400IU，上限 2000IU。")
                .score(score).reranked(Boolean.FALSE)
                .build();
    }
}
