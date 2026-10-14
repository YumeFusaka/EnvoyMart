package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.flow.FlowRegistry;
import yumefusaka.envoymart.agent.flow.IntentRouter;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.MockLLMProvider;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.llm.PlanWithIntent;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.loop.LoopBudget;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.memory.EpisodicMemory;
import yumefusaka.envoymart.agent.memory.ShortTermMemory;
import yumefusaka.envoymart.agent.memory.UserProfileStore;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.QueryRewriter;
import yumefusaka.envoymart.agent.rag.RAGEngine;
import yumefusaka.envoymart.agent.core.task.TaskCheckpoint;
import yumefusaka.envoymart.agent.core.task.TaskStateStore;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写操作账本的<b>接线</b>：确认执行留下的「对哪个目标做过写操作」，必须活着走到续作轮。
 * <p>
 * 现场是 {@code chat:hist:u1001:u1001-1791529067350} 第 13 轮：用户说
 * 「那两个商品我都来一个吧，原版来120片版本的」。系统先拦住并确认了原版 120 片
 * （{@code skuId=39}），续作轮本该去加免疫配方版——<b>却又把 39 填了一遍</b>，
 * 用户再点一次确认，同一件商品被加了两次，免疫配方版一件没加。
 * <p>
 * 图内的判重逻辑早已就位（见 {@link AgentActionReconciliationTest}），
 * 缺的是<b>账本本身没接上线</b>：写操作在图外执行，图内推不出来。
 * 这条测试锁的正是那根线——从确认执行、经断点、到续作轮的完整链路。
 * <p>
 * <b>为什么必须是端到端而不是再补一条图内单测。</b>图内判重此前已经全绿，
 * 而缺陷照样发生在生产会话里——因为调用方压根没把账本传进去。
 * 「图内逻辑对」与「这根线接上了」是两件事，只有端到端能同时证明。
 */
class AgentWriteLedgerWiringTest {

    private static final LLMConfig CONFIG = LLMConfig.builder().model("stub").build();

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

    /** 逐轮弹出计划的桩：首次规划给第一份，续作轮给第二份 */
    private static final class QueuePlanProvider implements LLMProvider {
        private final Deque<List<PlanStep>> rounds = new ArrayDeque<>();
        private final AtomicInteger planCalls = new AtomicInteger();

        @SafeVarargs
        QueuePlanProvider(List<PlanStep>... plans) {
            for (List<PlanStep> plan : plans) {
                rounds.add(plan);
            }
        }

        @Override
        public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            return LLMResponse.builder().content("作答").build();
        }

        @Override
        public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                         Map<String, Object> toolContext) {
            return LLMResponse.builder().content("作答").build();
        }

        @Override
        public List<PlanStep> plan(String message, List<ToolDefinition> tools, String context) {
            return rounds.isEmpty() ? List.of() : rounds.peek();
        }

        @Override
        public PlanWithIntent planWithIntent(String message, List<ToolDefinition> tools, String context) {
            planCalls.incrementAndGet();
            return new PlanWithIntent(rounds.isEmpty() ? List.of() : rounds.poll(), false);
        }
    }

    private static Tool lookup(String name, long skuId) {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name(name).description("检索").parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.builder().success(true).output("SKU" + skuId)
                        .rawData(new AgentGraphReplanTest.Sku(skuId)).build();
            }
        };
    }

    private static Tool cartAdd(List<Map<String, Object>> calls) {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("cart_add").description("加购")
                        .requiresConfirmation(true).parameters(Map.of()).build();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                calls.add(call.getArguments());
                // operationState 必须显式给 COMMITTED：确认轮把「没有完整权威回执」的
                // 结果一律当成 UNKNOWN（见 Agent#runSignedAction），而 UNKNOWN 会让
                // 「本批是否全部落地」判为否、续作轮压根不会启动——本用例要验的正是续作轮
                return ToolResult.builder().success(true)
                        .operationState(ToolResult.OperationState.COMMITTED)
                        .output("已加购").build();
            }
        };
    }

    @Test
    void 续作轮不得再把已确认落地的商品填一遍() {
        List<Map<String, Object>> executedWrites = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register(lookup("lookup_a", 39L));
        registry.register(lookup("lookup_b", 29L));
        registry.register(cartAdd(executedWrites));

        // 首次计划：查两件商品，加购原版（skuId=39），再加购免疫配方版（skuId=29）。
        // 免疫配方版那一步依赖原版那一步——正是这个链式依赖让「本批只批一件」，
        // 与现场形态一致（pending_tools 只有一条 cart_add）
        QueuePlanProvider provider = new QueuePlanProvider(
                List.of(
                        PlanStep.builder().tool("lookup_a").arguments(Map.of("query", "原版")).reason("查原版").build(),
                        PlanStep.builder().tool("lookup_b").arguments(Map.of("query", "免疫配方")).reason("查免疫配方").build(),
                        PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 39, "quantity", 1))
                                .dependsOn(List.of(0)).reason("把原版加入购物车").build(),
                        PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 29, "quantity", 1))
                                .dependsOn(List.of(2)).reason("把免疫配方版加入购物车").build()),
                // 续作轮：模型把上一批那个 SKU 又填了一遍（实测就是这样）
                List.of(PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 39, "quantity", 1))
                        .reason("把免疫配方版加入购物车").build()));

        AgentGraph graph = new AgentGraph(provider, CONFIG, registry, Executors.newVirtualThreadPerTaskExecutor());
        MockLLMProvider llm = new MockLLMProvider();
        Agent agent = new Agent(
                Agent.Config.builder().memoryConsolidationEnabled(false).build(),
                registry,
                new IntentRouter(llm, CONFIG, new FlowRegistry()),
                graph,
                new ShortTermMemory(16),
                new EpisodicMemory(),
                new UserProfileStore(),
                NO_KNOWLEDGE,
                null,
                new QueryRewriter(llm, CONFIG),
                new InMemoryTaskStateStore());

        // 第一轮：中断，等确认
        Agent.AgentResponse interrupted = agent.chat("u1", "s1", "那两个商品我都来一个吧，原版来120片版本的", null);
        assertThat(interrupted.getApprovalToken()).as("中断轮必须签发令牌").isNotBlank();
        assertThat(interrupted.getPendingActions())
                .as("第一批只该有链上那一条——免疫配方版依赖它，还轮不到")
                .containsExactly("cart_add(quantity=1, skuId=39)");

        // 第二轮：用户点确认。执行原版，然后续作轮去处理免疫配方版
        Agent.AgentResponse confirmed = agent.chat("u1", "s1", "确认执行", interrupted.getApprovalToken());

        assertThat(provider.planCalls.get())
                .as("续作轮必须真的跑过——没跑的话下面那条断言是空过的")
                .isEqualTo(2);
        assertThat(confirmed.getPendingActions())
                .as("续作轮把已落地的 SKU 又填了一遍，系统必须拒绝生成第二张确认卡。"
                        + "少了这一道，用户再点一次确认，同一件商品就被加了两次")
                .isNullOrEmpty();
        assertThat(executedWrites)
                .as("只有第一轮批准过的那一次能真正落地")
                .hasSize(1)
                .first()
                .isEqualTo(Map.of("skuId", 39, "quantity", 1));
    }

    @Test
    void 续作轮填对另一个目标时照常给出确认卡() {
        List<Map<String, Object>> executedWrites = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register(lookup("lookup_a", 39L));
        registry.register(lookup("lookup_b", 29L));
        registry.register(cartAdd(executedWrites));

        QueuePlanProvider provider = new QueuePlanProvider(
                List.of(
                        PlanStep.builder().tool("lookup_a").arguments(Map.of("query", "原版")).reason("查原版").build(),
                        PlanStep.builder().tool("lookup_b").arguments(Map.of("query", "免疫配方")).reason("查免疫配方").build(),
                        PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 39, "quantity", 1))
                                .dependsOn(List.of(0)).reason("把原版加入购物车").build(),
                        PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 29, "quantity", 1))
                                .dependsOn(List.of(2)).reason("把免疫配方版加入购物车").build()),
                // 续作轮：换成免疫配方版那个真实 SKU
                List.of(PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 29, "quantity", 1))
                        .reason("把免疫配方版加入购物车").build()));

        AgentGraph graph = new AgentGraph(provider, CONFIG, registry, Executors.newVirtualThreadPerTaskExecutor());
        MockLLMProvider llm = new MockLLMProvider();
        Agent agent = new Agent(
                Agent.Config.builder().memoryConsolidationEnabled(false).build(),
                registry,
                new IntentRouter(llm, CONFIG, new FlowRegistry()),
                graph,
                new ShortTermMemory(16),
                new EpisodicMemory(),
                new UserProfileStore(),
                NO_KNOWLEDGE,
                null,
                new QueryRewriter(llm, CONFIG),
                new InMemoryTaskStateStore());

        Agent.AgentResponse interrupted = agent.chat("u1", "s1", "那两个商品我都来一个吧，原版来120片版本的", null);
        Agent.AgentResponse confirmed = agent.chat("u1", "s1", "确认执行", interrupted.getApprovalToken());

        assertThat(confirmed.getPendingActions())
                .as("续作该做的正是「另一件商品」——判重若把它也拦下，这条链就废了")
                .containsExactly("cart_add(quantity=1, skuId=29)");
        assertThat(confirmed.getApprovalToken()).as("新的确认卡要有自己的令牌").isNotBlank();
        assertThat(executedWrites)
                .as("新一张卡片还没被批准，不该有任何副作用落地")
                .hasSize(1);
    }

    @Test
    void 账本随断点跨批累积() {
        List<Map<String, Object>> executedWrites = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register(lookup("lookup_a", 39L));
        registry.register(lookup("lookup_b", 29L));
        registry.register(lookup("lookup_c", 49L));
        registry.register(cartAdd(executedWrites));

        QueuePlanProvider provider = new QueuePlanProvider(
                List.of(
                        PlanStep.builder().tool("lookup_a").arguments(Map.of("query", "原版")).reason("查原版").build(),
                        PlanStep.builder().tool("lookup_b").arguments(Map.of("query", "免疫配方")).reason("查免疫配方").build(),
                        PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 39, "quantity", 1))
                                .dependsOn(List.of(0)).reason("把原版加入购物车").build(),
                        PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 29, "quantity", 1))
                                .dependsOn(List.of(2)).reason("把免疫配方版加入购物车").build()),
                // 续作轮换成免疫配方版（合法），但它后面还链着第三步 → 再产生一次续作
                List.of(
                        PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 29, "quantity", 1))
                                .reason("把免疫配方版加入购物车").build(),
                        PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 49, "quantity", 1))
                                .dependsOn(List.of(0)).reason("再补一件").build()),
                // 第二批的续作轮：又把最早那件填回来 —— 跨批累积的账本必须仍然记得它
                List.of(PlanStep.builder().tool("cart_add").arguments(Map.of("skuId", 39, "quantity", 1))
                        .reason("把原版加入购物车").build()));

        AgentGraph graph = new AgentGraph(provider, CONFIG, registry, Executors.newVirtualThreadPerTaskExecutor());
        MockLLMProvider llm = new MockLLMProvider();
        TaskStateStore store = new InMemoryTaskStateStore();
        Agent agent = new Agent(
                Agent.Config.builder().memoryConsolidationEnabled(false).build(),
                registry,
                new IntentRouter(llm, CONFIG, new FlowRegistry()),
                graph,
                new ShortTermMemory(16),
                new EpisodicMemory(),
                new UserProfileStore(),
                NO_KNOWLEDGE,
                null,
                new QueryRewriter(llm, CONFIG),
                store);

        Agent.AgentResponse first = agent.chat("u1", "s1", "那两个商品我都来一个吧，原版来120片版本的", null);
        Agent.AgentResponse second = agent.chat("u1", "s1", "确认执行", first.getApprovalToken());
        assertThat(second.getPendingActions())
                .as("第二批是另一件商品，照常给卡片")
                .containsExactly("cart_add(quantity=1, skuId=29)");

        Agent.AgentResponse third = agent.chat("u1", "s1", "确认执行", second.getApprovalToken());

        assertThat(provider.planCalls.get())
                .as("第三轮续作必须真的跑过")
                .isEqualTo(3);
        assertThat(third.getPendingActions())
                .as("账本要跨批累积：第二批之后，第一批那个 SKU 仍在账上，"
                        + "续作轮再填它一样要被拒——只记「上一批」是不够的")
                .isNullOrEmpty();
        assertThat(executedWrites)
                .as("两批各落地一次，第三批没落地")
                .hasSize(2);
    }
}