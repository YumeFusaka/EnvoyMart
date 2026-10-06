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
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具调用纪律必须写进系统提示词，且只在真的有工具可调时写。
 *
 * <p><b>为什么需要这条门禁（2026-10-04 实测）：</b>流式路径下，模型在拿到
 * {@code order_query} 结果后没有继续发出本该紧随其后的 {@code order_cancel}，
 * 而是输出了一段「我先确认一下……确定要取消这笔订单吗？」的过渡文本就收尾了。
 * 实测 3 次复现 1 次成功、2 次失败；同一条查询走非流式路径时每次都正常发出工具调用。
 * 两条路径同一提示词、同一模型、同一工具表，唯一的差别是流式的过渡文本实时推给了用户——
 * 模型看到自己已经把话说完整，就把「问用户」当成了收尾。
 * <p>而「要不要用户确认」在本项目里由服务端审批闸口决定，不由模型在话里问一遍。
 * <p><b>另一条同样重要：没有工具时不能写。</b>提示词指向一个不存在的工具，
 * 模型会去调它，而这件事在日志里表现为「模型没有调用任何工具」——
 * 与 {@code KnowledgePrompt} 里「再检索工具不在册就不给指路」是同一条原则。
 */
class AgentToolDisciplinePromptTest {

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

    /** 拦下图收到的系统提示词 —— 它才是真正送到模型面前的东西 */
    private static class CapturingGraph extends AgentGraph {
        volatile String lastSystemPrompt = "";

        CapturingGraph() {
            super(new MockLLMProvider(), LLMConfig.builder().model("stub").build(), new ToolRegistry(),
                    Executors.newVirtualThreadPerTaskExecutor());
        }

        @Override
        public GraphResult run(String userId, String message, String systemPrompt, List<ChatMessage> conversation,
                               LoopGuard guard, Consumer<String> onChunk, ToolProgressListener progress) {
            this.lastSystemPrompt = systemPrompt == null ? "" : systemPrompt;
            return GraphResult.builder().answer("stub").steps(List.of()).build();
        }
    }

    private static Tool stubTool() {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("order_query").description("查订单").build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.builder().success(true).output("ok").build();
            }
        };
    }

    private String promptSeenByGraph(boolean withTools) {
        CapturingGraph graph = new CapturingGraph();
        ToolRegistry tools = new ToolRegistry();
        if (withTools) {
            tools.register(stubTool());
        }
        MockLLMProvider llm = new MockLLMProvider();
        LLMConfig llmConfig = LLMConfig.builder().model("stub").build();
        Agent agent = new Agent(
                Agent.Config.builder().memoryConsolidationEnabled(false).build(),
                tools,
                new IntentRouter(llm, llmConfig, new FlowRegistry()),
                graph,
                new ShortTermMemory(16),
                new EpisodicMemory(),
                new UserProfileStore(),
                NO_KNOWLEDGE,
                null,
                new QueryRewriter(llm, llmConfig));
        agent.chat("u1001", "s-prompt", "帮我取消订单 1", null);
        return graph.lastSystemPrompt;
    }

    @Test
    void 有工具在册时系统提示词必须写明工具调用纪律() {
        String prompt = promptSeenByGraph(true);

        assertThat(prompt)
                .as("有工具可调时，提示词必须约束「先发工具调用、不要先说一句就停下来等回复」——"
                        + "流式路径下缺了这条约束，模型会把过渡文本当成收尾（实测 2/3 复现）")
                .contains("工具调用纪律");
        assertThat(prompt)
                .as("不可撤销操作必须由模型先发工具、由服务端审批闸口接管，而不是模型在话里问一遍")
                .contains("系统会在真正执行前让用户确认");
    }

    /**
     * 输出语言约束必须在系统提示词里，且<b>不带条件</b>。
     * <p>
     * 实测（5 次采样 3 次全英文）：用户消息里带拉丁字母串（商品编号、型号）时，
     * 模型会镜像输入语言，整段作答漂成英文。原先唯一的语言指令在收口合成那一步，
     * 只覆盖有工具结果的路径——ReAct 直答与纯对话两条路都看不住它。
     * <p>
     * 断言「一律使用简体中文」而不是「如果用户说中文就答中文」：后一种写法把判断权
     * 交回给模型，而它判断的正是它刚才判错的那件事。
     */
    @Test
    void 系统提示词必须约束输出语言为简体中文() {
        assertThat(promptSeenByGraph(true))
                .as("带工具时也要有语言约束——漂移恰好发生在有工具调用的那几轮")
                .contains("输出语言")
                .contains("简体中文");
        assertThat(promptSeenByGraph(false))
                .as("没有工具时同样要有：纯对话路径原先一条语言指令都没有")
                .contains("简体中文");
    }

    @Test
    void 没有工具在册时不得写入工具调用纪律() {
        String prompt = promptSeenByGraph(false);

        assertThat(prompt)
                .as("没有工具可调时提示词不能指向不存在的工具：模型会去调它，"
                        + "而这件事在日志里只表现为「模型没有调用任何工具」")
                .doesNotContain("工具调用纪律");
    }

    /**
     * 症状类提问的三条规则必须写进系统提示词。
     * <p>
     * 这三条各对应一次实测失效，而且都不带条件（不依赖「模型愿不愿意这么做」）：
     * <ol>
     *   <li><b>先搜商品再说有没有</b>：实测「最近肠道不好，应该吃什么药」一轮里
     *       {@code product_search} 一次都没调，直接答「知识库里没有用药依据」。</li>
     *   <li><b>澄清必须能缩小结果集</b>：「肠道不好」可能是便秘/腹泻/腹胀，
     *       三者的商品完全不同。写成「请说得详细点」会把归纳的活推回给用户，
     *       而且问十轮结果集都不变。</li>
     *   <li><b>说「没有」不得越过资料边界</b>：可以说「平台没有收录」，
     *       不可以说「你这种情况不能吃」——后者是医学结论。</li>
     * </ol>
     */
    @Test
    void 症状类提问的三条规则必须写进系统提示词() {
        String prompt = promptSeenByGraph(true);

        assertThat(prompt)
                .as("必须先调商品检索：失效发生在模型决定不调工具的那一刻，"
                        + "所以这条只能写在系统提示词里，工具描述那时还没被读到")
                .contains("症状与健康类提问")
                .contains("必须先调用商品检索工具");
        assertThat(prompt)
                .as("澄清问题要给出候选（缩小结果集），并明确禁止「请说得详细点」式的反问")
                .contains("能缩小结果集")
                .contains("不要问");
        assertThat(prompt)
                .as("说「没有」只能指平台资料的覆盖情况，不得下医学结论")
                .contains("「没有」的正确说法")
                .contains("你这种情况不能吃");
        assertThat(prompt)
                .as("资料里写了禁忌时按要求复述并建议就医——判据是资料有没有写，不是话题敏不敏感")
                .contains("照着资料说");
    }

    /** 澄清规则的措辞不能被压成「一律先问」：能直接答的时候还要照常给推荐 */
    @Test
    void 症状类规则不得退化成一律先反问() {
        String prompt = promptSeenByGraph(true);

        assertThat(prompt)
                .as("四要素（推荐/为什么/作用/提醒）必须保留——否则模型会把「先澄清」"
                        + "当成「一律反问」，而用户问的是「该吃什么」")
                .contains("推荐哪一个商品")
                .contains("不能替代药物治疗");
    }
}