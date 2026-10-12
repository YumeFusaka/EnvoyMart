package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.flow.FlowRegistry;
import yumefusaka.envoymart.agent.flow.IntentRouter;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
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
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 给定规划的执行契约回放：真实运行 AgentGraph、Agent 与 ToolRegistry，仅替换模型及下游。
 * 这些用例不能证明真实模型能从自然语言生成同样的规划，也不能代替重启或端到端验收。
 */
class AgentComplexDagReplayTest {
    private static final LLMConfig CONFIG = LLMConfig.builder().model("确定性回放").build();
    private static final RAGEngine NO_KNOWLEDGE = new RAGEngine() {
        @Override public void ingest(Document document) { }
        @Override public void ingestBatch(List<Document> documents) { }
        @Override public List<DocumentChunk> retrieve(String query, int topK) { return List.of(); }
    };

    static Stream<ReplayCase> cases() throws Exception {
        try (InputStream input = AgentComplexDagReplayTest.class.getResourceAsStream(
                "/complex-dialogues/dag-replay.json")) {
            assertThat(input).as("回放夹具必须随测试资源加载").isNotNull();
            List<ReplayCase> cases = new ObjectMapper().readValue(input, new TypeReference<>() { });
            assertThat(cases).extracting(ReplayCase::id).doesNotHaveDuplicates();
            return cases.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void 真实执行图和审批轮必须符合回放契约(ReplayCase scenario) {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Invocation> invocations = Collections.synchronizedList(new ArrayList<>());
            List<ExpectedCall> effects = Collections.synchronizedList(new ArrayList<>());
            ToolRegistry registry = new ToolRegistry();
            Map<String, Receipt> receipts = new java.util.concurrent.ConcurrentHashMap<>();
            scenario.tools().forEach(fixture -> registry.register(recordingTool(fixture, invocations, effects, receipts)));
            registry.validateContracts();
            ScriptedProvider provider = new ScriptedProvider(scenario.plans());
            AgentGraph graph = new AgentGraph(provider, CONFIG, registry, executor);
            Agent agent = new Agent(Agent.Config.builder().memoryConsolidationEnabled(false).build(),
                    registry, new IntentRouter(provider, CONFIG, new FlowRegistry()), graph,
                    new ShortTermMemory(32), new EpisodicMemory(), new UserProfileStore(),
                    NO_KNOWLEDGE, null, new QueryRewriter(provider, CONFIG));
            String latestToken = null;

            for (Turn turn : scenario.turns()) {
                CancellationProbe progress = new CancellationProbe(turn.cancelBefore());
                provider.afterPlan = turn.cancelAfterPlan() ? () -> progress.cancelled.set(true) : () -> { };
                String token = "latest".equals(turn.token()) ? latestToken : null;
                if ("latest".equals(turn.token())) {
                    assertThat(token).as("确认轮必须使用真实上轮签发的令牌").isNotBlank();
                }
                if (turn.cancelled()) {
                    assertThatThrownBy(() -> execute(scenario.mode(), agent, graph, turn, token, progress))
                            .satisfies(error -> assertThat(AgentCancelledException.isCancellation(error))
                                    .as("取消必须穿过图框架而不能变成普通失败").isTrue());
                } else {
                    TurnResult result = execute(scenario.mode(), agent, graph, turn, token, progress);
                    assertThat(result.stage()).as("%s：阶段", turn.message()).isEqualTo(turn.stage());
                    assertThat(result.pending()).as("%s：待确认的真实参数", turn.message())
                            .containsExactlyInAnyOrderElementsOf(turn.pending());
                    if (turn.replyContains() != null) {
                        assertThat(result.reply()).contains(turn.replyContains());
                    }
                    if (turn.replyNotContains() != null) {
                        assertThat(result.reply()).doesNotContain(turn.replyNotContains());
                    }
                    if (turn.states() != null) {
                        assertThat(result.executions()).extracting(item -> item.getOperationState().name())
                                .as("%s：权威提交/拒绝/未知状态", turn.message()).containsExactlyElementsOf(turn.states());
                        if (turn.states().stream().anyMatch(state -> !"COMMITTED".equals(state))) {
                            assertThat(result.executions()).allSatisfy(item -> assertThat(item.isSuccess()).isFalse());
                            assertThat(result.token()).as("未知或拒绝不得消费原审批签名").isEqualTo(token);
                            assertThat(result.stage()).isEqualTo("WAITING_USER");
                            assertThat(effects).as("未知和拒绝没有已证实副作用").hasSize(turn.effects());
                        }
                    }
                    if (result.token() != null) {
                        latestToken = result.token();
                    }
                }
                assertThat(effects).as("%s：本轮结束时实际成功写入次数", turn.message())
                        .hasSize(turn.effects());
            }

            assertThat(provider.planCalls.get()).as("规划含重规划次数").isEqualTo(scenario.plans().size());
            assertThat(invocations.stream().map(Invocation::expected).toList())
                    .as("每次实际下游调用的工具、用户和参数，不能靠最终回答推断")
                    .containsExactlyInAnyOrderElementsOf(scenario.calls());
            assertThat(invocations).as("声明的下游响应必须全部实际消费")
                    .hasSize(scenario.tools().stream().mapToInt(tool -> tool.responses().size()).sum());
            assertThat(effects).as("最终成功副作用，不把尝试或业务拒绝算作成功")
                    .containsExactlyInAnyOrderElementsOf(scenario.effects());
            assertThat(invocations).allSatisfy(invocation -> {
                assertThat(invocation.operationId()).as("执行器必须注入操作标识").isNotBlank();
                assertThat(invocation.confirmed()).as("只有审批轮的真实写工具才能携带已确认标志")
                        .isEqualTo(invocation.write());
                if (invocation.arguments().containsKey("skuId")) {
                    assertThat(invocation.arguments().get("skuId")).as("引用值保留数值类型").isInstanceOf(Number.class);
                }
            });
            if ("distinct".equals(scenario.operations())) {
                assertThat(invocations.stream().map(Invocation::operationId).toList()).doesNotHaveDuplicates();
            } else if ("same".equals(scenario.operations())) {
                assertThat(invocations.stream().map(Invocation::operationId).distinct().toList()).hasSize(1);
            }
            if (scenario.replanContains() != null) {
                assertThat(provider.planMessages).anySatisfy(message -> assertThat(message).contains(scenario.replanContains()));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static TurnResult execute(String mode, Agent agent, AgentGraph graph, Turn turn,
                                      String token, CancellationProbe progress) {
        if ("agent".equals(mode)) {
            Agent.AgentResponse response = turn.recoveryMode() == null
                    ? agent.chatStream(turn.user(), turn.session(), turn.message(), token, null, progress)
                    : agent.recoverApproved(turn.user(), turn.session(), token, "RETRY".equals(turn.recoveryMode()), progress);
            List<ExpectedCall> pending = response.getPendingActionDetails() == null ? List.of()
                    : response.getPendingActionDetails().stream().map(detail -> new ExpectedCall(
                            String.valueOf(detail.get("tool")), turn.user(), argumentsOf(detail))).toList();
            return new TurnResult(response.getStage().name(), response.getReply(), response.getApprovalToken(), pending,
                    response.getToolExecutions() == null ? List.of() : response.getToolExecutions());
        }
        AgentGraph.GraphResult response = graph.run(turn.user(), turn.session(), turn.message(), "", List.of(),
                new LoopGuard(), null, progress, AgentGraph.RetrievalResult::empty);
        List<ExpectedCall> pending = response.getPendingActions() == null ? List.of()
                : response.getPendingActions().stream().map(action -> expected(action, turn.user())).toList();
        return new TurnResult(response.getStage().name(), response.getAnswer(), null, pending, List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argumentsOf(Map<String, Object> detail) {
        return (Map<String, Object>) detail.get("arguments");
    }

    private static ExpectedCall expected(PendingAction action, String user) {
        return new ExpectedCall(action.tool(), user, action.arguments());
    }

    private static Tool recordingTool(ToolFixture fixture, List<Invocation> invocations,
                                      List<ExpectedCall> effects, Map<String, Receipt> receipts) {
        AtomicInteger executions = new AtomicInteger();
        return new Tool() {
            @Override public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name(fixture.name()).description("回放下游契约")
                        .requiresConfirmation(fixture.write()).acceptsOperationId(true)
                        .idempotencyPolicy(ToolDefinition.IdempotencyPolicy.IDEMPOTENT).parameters(Map.of()).build();
            }

            @Override public ToolResult execute(ToolCall call) {
                if (fixture.write()) {
                    synchronized (receipts) {
                        ToolResult prior = recover(call);
                        if (prior.getOperationState() != ToolResult.OperationState.NOT_FOUND) return prior;
                        ToolResult first = executeFirst(call);
                        if (first.getOperationState() == ToolResult.OperationState.COMMITTED) {
                            receipts.put(key(call), new Receipt(new ExpectedCall(fixture.name(), call.getUserId(), call.getArguments()), first));
                        }
                        return first;
                    }
                }
                return executeFirst(call);
            }

            private ToolResult executeFirst(ToolCall call) {
                int index = executions.getAndIncrement();
                assertThat(index).as("%s 未声明的下游调用不能回退为成功", fixture.name())
                        .isLessThan(fixture.responses().size());
                Response response = fixture.responses().get(index);
                ExpectedCall expected = new ExpectedCall(fixture.name(), call.getUserId(), call.getArguments());
                invocations.add(new Invocation(expected, call.getOperationId(), call.isConfirmed(), fixture.write()));
                if (fixture.write() && response.success()) {
                    effects.add(expected);
                }
                return ToolResult.builder().success(response.success()).noData(response.noData())
                        .operationState(!fixture.write() ? null : response.success() ? ToolResult.OperationState.COMMITTED
                                : response.transientFailure() ? ToolResult.OperationState.UNKNOWN : ToolResult.OperationState.REJECTED)
                        .transientFailure(response.transientFailure()).output(response.output())
                        .errorMessage(response.error()).rawData(response.rawData()).build();
            }

            private String key(ToolCall call) { return call.getUserId() + "|" + call.getOperationId(); }

            @Override public ToolResult recover(ToolCall call) {
                Receipt found = receipts.get(key(call));
                if (found == null) return ToolResult.builder().success(false).operationState(ToolResult.OperationState.NOT_FOUND)
                        .errorMessage("没有可核验的原回执").build();
                ExpectedCall expected = new ExpectedCall(fixture.name(), call.getUserId(), call.getArguments());
                if (!expected.equals(found.expected())) return ToolResult.builder().success(false)
                        .operationState(ToolResult.OperationState.REJECTED).errorMessage("原操作参数或工具冲突").build();
                return found.result().toBuilder().receiptReplayed(true).build();
            }
        };
    }

    @Test
    void 下游回执替身跨注册中心重放保持原参数身份且副作用只产生一次() {
        var invocations = new ArrayList<Invocation>(); var effects = new ArrayList<ExpectedCall>();
        Map<String, Receipt> receipts = new java.util.concurrent.ConcurrentHashMap<>();
        ToolFixture fixture = new ToolFixture("cart_add", true, List.of(new Response(true, false, false, "首次加入2件", null, null)));
        ToolRegistry first = new ToolRegistry(); first.register(recordingTool(fixture, invocations, effects, receipts));
        ToolCall call = new ToolCall("call-1", "cart_add", Map.of("skuId", 101, "quantity", 2), true, "u1", "original-op");
        ToolResult initial = first.execute(call);
        ToolRegistry restarted = new ToolRegistry(); restarted.register(recordingTool(fixture, invocations, effects, receipts));
        ToolResult repeated = restarted.execute(new ToolCall("call-2", "cart_add", call.getArguments(), true, "u1", "original-op"));
        ToolResult queried = restarted.recover(call);
        assertThat(initial.getOperationState()).isEqualTo(ToolResult.OperationState.COMMITTED);
        assertThat(repeated.isReceiptReplayed()).isTrue();
        assertThat(queried.isReceiptReplayed()).isTrue();
        assertThat(repeated.getOutput()).isEqualTo(initial.getOutput());
        assertThat(effects).hasSize(1); assertThat(invocations).hasSize(1);
        assertThat(restarted.recover(new ToolCall("wrong", "cart_add", Map.of("skuId", 202), true, "u1", "original-op"))
                .getOperationState()).isEqualTo(ToolResult.OperationState.REJECTED);
        assertThat(restarted.recover(new ToolCall("other", "cart_add", call.getArguments(), true, "u2", "original-op"))
                .getOperationState()).isEqualTo(ToolResult.OperationState.NOT_FOUND);
    }

    private static final class ScriptedProvider implements LLMProvider {
        private final List<Planning> plans;
        private final AtomicInteger planCalls = new AtomicInteger();
        private final List<String> planMessages = new ArrayList<>();
        private Runnable afterPlan = () -> { };

        private ScriptedProvider(List<Planning> plans) { this.plans = plans; }
        @Override public boolean supportsReasoning() { return false; }
        @Override public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            return LLMResponse.builder().content("确定性回放回答").build();
        }
        @Override public List<PlanStep> plan(String message, List<ToolDefinition> tools, String context) {
            int index = planCalls.getAndIncrement();
            assertThat(index).as("不能用空计划掩盖额外重规划").isLessThan(plans.size());
            planMessages.add(message);
            Planning planning = plans.get(index);
            if (planning.messageContains() != null) {
                assertThat(message).as("规划必须收到本轮用户输入或真实失败简报").contains(planning.messageContains());
            }
            List<PlanStep> result = planning.steps().stream().map(step -> PlanStep.builder().tool(step.tool())
                    .arguments(step.arguments()).dependsOn(step.dependsOn() == null ? List.of() : step.dependsOn())
                    .reason(step.reason() == null ? "执行回放任务" : step.reason()).operationId(step.operationId())
                    .optional(step.optional()).build()).toList();
            afterPlan.run();
            return result;
        }
    }

    private static final class CancellationProbe implements ToolProgressListener {
        private final AtomicBoolean cancelled;
        private CancellationProbe(boolean cancelled) { this.cancelled = new AtomicBoolean(cancelled); }
        @Override public void onStart(String tool) { }
        @Override public void onFinish(String tool, boolean success, boolean noData, long latencyMs) { }
        @Override public boolean cancelled() { return cancelled.get(); }
    }

    record ReplayCase(String id, String name, String mode, List<Planning> plans, List<ToolFixture> tools,
                      List<Turn> turns, List<ExpectedCall> calls, List<ExpectedCall> effects,
                      String operations, String replanContains) {
        @Override public String toString() { return id + " " + name; }
    }
    record Planning(String messageContains, List<Step> steps) { }
    record Step(String tool, Map<String, Object> arguments, List<Integer> dependsOn,
                String reason, String operationId, Boolean optional) {
        Step { optional = Boolean.TRUE.equals(optional); }
    }
    record ToolFixture(String name, Boolean write, List<Response> responses) {
        ToolFixture { write = Boolean.TRUE.equals(write); }
    }
    record Response(boolean success, Boolean noData, Boolean transientFailure, String output,
                    String error, Object rawData) {
        Response {
            noData = Boolean.TRUE.equals(noData);
            transientFailure = Boolean.TRUE.equals(transientFailure);
        }
    }
    record Turn(String user, String session, String message, String token, Boolean cancelBefore,
                Boolean cancelAfterPlan, Boolean cancelled, String stage, List<ExpectedCall> pending,
                int effects, String replyContains, String replyNotContains, List<String> states, String recoveryMode) {
        Turn {
            cancelBefore = Boolean.TRUE.equals(cancelBefore);
            cancelAfterPlan = Boolean.TRUE.equals(cancelAfterPlan);
            cancelled = Boolean.TRUE.equals(cancelled);
        }
    }
    record ExpectedCall(String tool, String user, Map<String, Object> arguments) { }
    record Invocation(ExpectedCall expected, String operationId, boolean confirmed, boolean write) {
        Map<String, Object> arguments() { return expected.arguments(); }
    }
    record Receipt(ExpectedCall expected, ToolResult result) { }
    record TurnResult(String stage, String reply, String token, List<ExpectedCall> pending, List<ToolExecution> executions) { }
}
