package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.flow.DeterministicFlow;
import yumefusaka.envoymart.agent.flow.FlowContext;
import yumefusaka.envoymart.agent.flow.FlowRegistry;
import yumefusaka.envoymart.agent.flow.FlowResult;
import yumefusaka.envoymart.agent.flow.IntentRouter;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
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
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性流程拿到的必须是<b>路由时用的那一句</b>。
 * <p>
 * 流程的契约是「先 {@code matches} 校验参数齐备，再 {@code execute} 用同一句话抽参数」——
 * 两处看的必须是同一个字符串。指代追问让这个前提变得不显然：
 * 「那个订单我要退掉」脱离上下文没有订单号，检索侧改写补成「订单 22 我要退掉」。
 * 若路由吃改写句、执行吃原话，就会出现<b>校验时参数齐备、执行时抽不到</b>，
 * 而 {@code AfterSaleFlow} 抽不到订单号时会把 null 塞进 {@code Map.of}（拒绝 null），
 * 抛出的 NPE 又被 Agent 的兜底 catch 吞掉：用户看到「智能助手暂时不可用」，
 * 日志里只有一条与真实原因无关的降级记录。整轮能力静默消失。
 * <p>
 * 这里用夹具复刻 AfterSaleFlow 的形状（{@code Map.of} + 同样的两段式抽取），
 * 因为 AfterSaleFlow 在 ai-service 里，agent-core 看不到它。
 */
class AgentFlowArgumentTest {

    private static final Pattern ORDER_ID = Pattern.compile("订单\\s*(\\d+)");

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

    /**
     * 既当改写器又当路由分类器的桩：按 system 提示词区分两次调用。
     * 真实模型两条都走同一个 {@code chat}，桩也必须这样，否则测不到接线。
     */
    private static class RewritingLLM implements LLMProvider {

        static final String REWRITTEN = "订单 22 我要退掉";
        /** 流程收到的话，断言就钉在这里 */
        volatile String seenByFlow;

        @Override
        public boolean supportsReasoning() {
            return true;
        }

        @Override
        public LLMResponse chat(List<yumefusaka.envoymart.agent.llm.ChatMessage> messages, LLMConfig config) {
            String system = messages.stream()
                    .filter(m -> m.getRole() == yumefusaka.envoymart.agent.llm.ChatMessage.Role.SYSTEM)
                    .map(yumefusaka.envoymart.agent.llm.ChatMessage::getContent)
                    .findFirst().orElse("");
            String content = system.contains("意图路由分类器")
                    ? "{\"flow\":\"after_sale\"}"
                    : REWRITTEN;
            return LLMResponse.builder()
                    .content(content)
                    .finishReason(LLMResponse.FinishReason.STOP)
                    .build();
        }
    }

    /** 复刻 AfterSaleFlow：matches 里抽一次参数，execute 里再抽一次 */
    private class StubAfterSaleFlow implements DeterministicFlow {

        @Override
        public String getName() {
            return "after_sale";
        }

        @Override
        public String getDescription() {
            return "用户想对某个具体订单申请退货";
        }

        @Override
        public boolean matches(String userMessage) {
            return userMessage != null && userMessage.contains("退") && orderIdOf(userMessage) != null;
        }

        @Override
        public FlowResult execute(FlowContext context) {
            String message = context.getUserMessage();
            llm.seenByFlow = message;
            // 与 AfterSaleFlow 同款：抽不到时把 null 交给 Map.of，它会抛 NPE
            Long orderId = orderIdOf(message);
            Map<String, Object> args = Map.of("orderId", orderId);
            return FlowResult.builder().success(true).output("订单 " + args.get("orderId") + " 可以退货。").build();
        }

        private Long orderIdOf(String message) {
            Matcher matcher = ORDER_ID.matcher(message);
            return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
        }
    }

    private final LLMConfig llmConfig = LLMConfig.builder().model("stub").build();
    private final RewritingLLM llm = new RewritingLLM();

    private Agent agent() {
        FlowRegistry flows = new FlowRegistry();
        flows.register(new StubAfterSaleFlow());
        return new Agent(
                Agent.Config.builder().memoryConsolidationEnabled(false).build(),
                new ToolRegistry(),
                new IntentRouter(llm, llmConfig, flows),
                new StubGraph(llmConfig),
                new ShortTermMemory(16),
                new EpisodicMemory(),
                new UserProfileStore(),
                NO_KNOWLEDGE,
                null,
                new QueryRewriter(llm, llmConfig));
    }

    /** 流程命中时图不该被走到；走到了说明路由没接管 */
    private static class StubGraph extends AgentGraph {
        StubGraph(LLMConfig llmConfig) {
            super(new yumefusaka.envoymart.agent.llm.MockLLMProvider(), llmConfig, new ToolRegistry(),
                    Executors.newVirtualThreadPerTaskExecutor());
        }

        @Override
        public GraphResult run(String userId, String message, String systemPrompt, List<yumefusaka.envoymart.agent.llm.ChatMessage> conversation,
                               LoopGuard guard, Consumer<String> onChunk, ToolProgressListener progress) {
            return GraphResult.builder().answer("stub-graph").steps(List.of()).build();
        }
    }

    @Test
    void 指代追问时流程与路由看的是同一句话() {
        Agent agent = agent();

        // 首轮给出订单号，让历史里有「订单 22」可供改写消解
        agent.chat("u1001", "s1", "订单 22 现在到哪了", null);
        // 追问：字面上没有订单号
        Agent.AgentResponse response = agent.chat("u1001", "s1", "那个订单我要退掉", null);

        assertThat(llm.seenByFlow)
                .as("流程必须先能通过参数校验、再能抽到参数——它只能看路由用的那一句")
                .isEqualTo(RewritingLLM.REWRITTEN);
        assertThat(response.getSource())
                .as("校验通过却没进流程，说明参数在执行时丢了，整轮被兜底 catch 吞掉")
                .isEqualTo("flow");
        assertThat(response.getReply()).contains("22");
    }

    @Test
    void 首轮不改写时流程照常拿到原话() {
        Agent agent = agent();

        Agent.AgentResponse response = agent.chat("u1001", "s2", "订单 7 我要退货", null);

        assertThat(llm.seenByFlow).isEqualTo("订单 7 我要退货");
        assertThat(response.getSource()).isEqualTo("flow");
    }
}
