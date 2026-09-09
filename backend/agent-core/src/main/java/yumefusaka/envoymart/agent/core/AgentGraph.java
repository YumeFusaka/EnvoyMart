package yumefusaka.envoymart.agent.core;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.loop.ToolContextKeys;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Agent 执行图 —— 用 LangGraph4j 的 {@link StateGraph} 显式编排。
 *
 * <pre>
 *   START → [plan] ──计划为空──→ [answer] → END
 *              │
 *          计划非空
 *              ↓
 *           [act] ──命中高危且未确认──→ END（中断，等用户确认后重入）
 *              │ 按依赖分层，无依赖的步骤并发执行
 *              ↓
 *         [evaluate] ──无阻塞失败 / 预算耗尽──→ [answer] → END
 *              │ 有非可选步骤失败
 *              ↓
 *          [replan] ──→ [act]（图里的环，受 LoopGuard 轮次上限约束）
 * </pre>
 *
 * 三层职责，互不越界：
 * <ul>
 *   <li><b>图</b>决定"下一步去哪"——节点与条件边显式注册，可导出 mermaid；</li>
 *   <li><b>节点</b>决定"这一步做什么"——节点内部才可能出现模型自主（answer 节点里的对话调用）；</li>
 *   <li><b>LoopGuard</b>决定"最多做多少"——同时约束本图的环与框架驱动的 ReAct 工具循环。</li>
 * </ul>
 * 实现上，请求级对象（对话历史、流式回调、循环护栏）由节点闭包捕获，
 * 图状态里只放可序列化的执行结果——既不把活对象塞进状态，也保留了图的快照能力。
 */
@Slf4j
public class AgentGraph {

    /**
     * 单个步骤的执行上限。
     * <p>
     * 工具内部是远程调用，下游卡住时不能无限期等——没有这个上限，一个慢下游
     * 会把整轮对话连同请求线程一起挂住。
     */
    private static final long STEP_TIMEOUT_MS = 15_000;

    private static final String NODE_PLAN = "plan";
    private static final String NODE_ACT = "act";
    private static final String NODE_EVALUATE = "evaluate";
    private static final String NODE_REPLAN = "replan";
    private static final String NODE_ANSWER = "answer";

    private static final String ROUTE_ACT = "act";
    private static final String ROUTE_ANSWER = "answer";
    private static final String ROUTE_EVALUATE = "evaluate";
    private static final String ROUTE_REPLAN = "replan";
    private static final String ROUTE_END = "end";

    private static final String KEY_PLAN = "plan";
    private static final String KEY_STEPS = "steps";
    private static final String KEY_PENDING = "pendingApproval";
    private static final String KEY_ANSWER = "answer";
    private static final String KEY_ROUND = "round";
    private static final String KEY_ROUTE = "route";

    private final LLMProvider llmProvider;
    private final LLMConfig llmConfig;
    private final ToolRegistry toolRegistry;
    private final ExecutorService executor;

    public AgentGraph(LLMProvider llmProvider, LLMConfig llmConfig,
                      ToolRegistry toolRegistry, ExecutorService executor) {
        this.llmProvider = llmProvider;
        this.llmConfig = llmConfig;
        this.toolRegistry = toolRegistry;
        this.executor = executor;
    }

    /** 导出图结构（mermaid），用于文档与讲解。 */
    public String toMermaid() {
        return compile(null).getGraph(
                GraphRepresentation.Type.MERMAID, "EnvoyMart Agent", false).content();
    }

    // ==================== 对外入口 ====================

    public GraphResult run(String userId, String message, String systemPrompt, List<ChatMessage> conversation,
                           boolean approved, LoopGuard guard, Consumer<String> onChunk) {

        GraphContext ctx = GraphContext.of(userId,
                message,
                systemPrompt == null ? "" : systemPrompt,
                conversation == null ? List.of() : conversation,
                approved,
                guard == null ? new LoopGuard() : guard,
                onChunk);

        Map<String, Object> initial = new HashMap<>();
        initial.put(KEY_STEPS, new ArrayList<GraphStep>());
        initial.put(KEY_PENDING, List.of());
        initial.put(KEY_ROUND, 1);

        GraphState finalState = invoke(compile(ctx), initial);

        List<String> pending = finalState.get(KEY_PENDING, List.<String>of());
        return GraphResult.builder()
                .answer(finalState.get(KEY_ANSWER, null))
                .plan(finalState.get(KEY_PLAN, List.<PlanStep>of()))
                .steps(finalState.get(KEY_STEPS, List.<GraphStep>of()))
                .toolExecutions(ctx.executions())
                .pendingApproval(pending.isEmpty() ? null : pending)
                .planRounds(finalState.get(KEY_ROUND, 1))
                .loops(ctx.guard().summary())
                .build();
    }

    private GraphState invoke(CompiledGraph<GraphState> compiled, Map<String, Object> initial) {
        GraphState last = null;
        for (NodeOutput<GraphState> output : compiled.stream(initial)) {
            last = output.state();
        }
        if (last == null) {
            throw new IllegalStateException("执行图未产出任何状态");
        }
        return last;
    }

    // ==================== 图定义 ====================

    /**
     * 按请求上下文组装一张图。
     * <p>
     * 节点是闭包，直接捕获请求级对象；状态里只留执行结果。
     * 图只有 5 个节点，重新组装的成本相对于一次模型调用可以忽略。
     */
    private CompiledGraph<GraphState> compile(GraphContext ctx) {
        try {
            return new StateGraph<>(GraphState::new)
                    .addNode(NODE_PLAN, AsyncNodeAction.node_async(state -> planNode(ctx, state)))
                    .addNode(NODE_ACT, AsyncNodeAction.node_async(state -> actNode(ctx, state)))
                    .addNode(NODE_EVALUATE, AsyncNodeAction.node_async(state -> evaluateNode(ctx, state)))
                    .addNode(NODE_REPLAN, AsyncNodeAction.node_async(state -> replanNode(ctx, state)))
                    .addNode(NODE_ANSWER, AsyncNodeAction.node_async(state -> answerNode(ctx, state)))
                    .addEdge(StateGraph.START, NODE_PLAN)
                    .addConditionalEdges(NODE_PLAN, route(),
                            Map.of(ROUTE_ACT, NODE_ACT, ROUTE_ANSWER, NODE_ANSWER))
                    .addConditionalEdges(NODE_ACT, route(),
                            Map.of(ROUTE_EVALUATE, NODE_EVALUATE, ROUTE_END, StateGraph.END))
                    .addConditionalEdges(NODE_EVALUATE, route(),
                            Map.of(ROUTE_ANSWER, NODE_ANSWER, ROUTE_REPLAN, NODE_REPLAN))
                    .addConditionalEdges(NODE_REPLAN, route(),
                            Map.of(ROUTE_ACT, NODE_ACT, ROUTE_ANSWER, NODE_ANSWER))
                    .addEdge(NODE_ANSWER, StateGraph.END)
                    .compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("组装 Agent 执行图失败", e);
        }
    }

    private AsyncEdgeAction<GraphState> route() {
        return AsyncEdgeAction.edge_async(state -> state.get(KEY_ROUTE, ROUTE_ANSWER));
    }

    // ==================== 节点 ====================

    /** 规划：产出显式计划；计划为空说明没有工具能帮上忙。 */
    private Map<String, Object> planNode(GraphContext ctx, GraphState state) {
        List<PlanStep> plan = filterRegistered(
                llmProvider.plan(ctx.message(), toolRegistry.listDefinitions(), ctx.systemPrompt()));
        log.debug("[Graph] plan: {}", plan.stream().map(PlanStep::getTool).toList());
        return updates(KEY_PLAN, plan, KEY_ROUND, 1,
                KEY_ROUTE, plan.isEmpty() ? ROUTE_ANSWER : ROUTE_ACT);
    }

    /** 执行：按依赖分层，同层并发；调用工具前拦截高危操作。 */
    private Map<String, Object> actNode(GraphContext ctx, GraphState state) {
        List<PlanStep> plan = state.get(KEY_PLAN, List.<PlanStep>of());
        List<GraphStep> steps = new ArrayList<>(state.get(KEY_STEPS, List.<GraphStep>of()));

        List<String> pending = executePlan(plan, state.get(KEY_ROUND, 1), ctx, steps);

        return updates(KEY_STEPS, steps, KEY_PENDING, pending,
                KEY_ROUTE, pending.isEmpty() ? ROUTE_EVALUATE : ROUTE_END);
    }

    /** 评估：纯规则判断，不调模型——只看有没有"非可选步骤失败"。 */
    private Map<String, Object> evaluateNode(GraphContext ctx, GraphState state) {
        List<GraphStep> steps = state.get(KEY_STEPS, List.<GraphStep>of());

        boolean blocked = steps.stream().anyMatch(step -> !step.isSuccess() && !step.isOptional());
        // 还有预算就允许再规划一轮，否则直接作答
        boolean canReplan = blocked && ctx.guard().allowPlanRound();

        log.debug("[Graph] evaluate blocked={} canReplan={} {}", blocked, canReplan, ctx.guard().summary());

        return updates(KEY_ROUND, state.get(KEY_ROUND, 1) + 1,
                KEY_ROUTE, canReplan ? ROUTE_REPLAN : ROUTE_ANSWER);
    }

    /** 重规划：把已完成步骤与失败原因交给模型，修正剩余计划。 */
    private Map<String, Object> replanNode(GraphContext ctx, GraphState state) {
        List<GraphStep> steps = state.get(KEY_STEPS, List.<GraphStep>of());
        List<PlanStep> plan = filterRegistered(replan(ctx, steps));
        log.debug("[Graph] replanned: {}", plan.stream().map(PlanStep::getTool).toList());
        return updates(KEY_PLAN, plan, KEY_ROUTE, plan.isEmpty() ? ROUTE_ANSWER : ROUTE_ACT);
    }

    /** 合成回答：有工具结果就基于结果作答；没有则直接对话（ReAct 所在的位置）。 */
    private Map<String, Object> answerNode(GraphContext ctx, GraphState state) {
        List<GraphStep> steps = state.get(KEY_STEPS, List.<GraphStep>of());
        String answer = steps.isEmpty() ? converse(ctx) : synthesize(ctx, steps);
        return updates(KEY_ANSWER, answer);
    }

    // ==================== 执行细节 ====================

    /** @return 待用户确认的高危工具；非空表示应中断 */
    private List<String> executePlan(List<PlanStep> plan, int round, GraphContext ctx,
                                     List<GraphStep> steps) {
        boolean[] done = new boolean[plan.size()];
        int finished = 0;

        while (finished < plan.size()) {
            List<Integer> ready = new ArrayList<>();
            for (int i = 0; i < plan.size(); i++) {
                if (done[i]) {
                    continue;
                }
                boolean depsMet = plan.get(i).getDependsOn().stream()
                        .allMatch(d -> d >= 0 && d < done.length && done[d]);
                if (depsMet) {
                    ready.add(i);
                }
            }
            if (ready.isEmpty()) {
                log.warn("[Graph] unsatisfiable dependencies, {} step(s) skipped", plan.size() - finished);
                break;
            }

            // 调用工具前的拦截：发生在执行之前，这是 ReAct 结构上做不到的位置
            if (!ctx.approved()) {
                List<String> risky = ready.stream()
                        .map(plan::get)
                        .filter(this::requiresConfirmation)
                        .map(PlanStep::getTool)
                        .distinct()
                        .toList();
                if (!risky.isEmpty()) {
                    return risky;
                }
            }

            invokeBatch(plan, ready, round, ctx, steps);
            ready.forEach(i -> done[i] = true);
            finished += ready.size();
        }
        return List.of();
    }

    private void invokeBatch(List<PlanStep> plan, List<Integer> batch, int round, GraphContext ctx,
                             List<GraphStep> steps) {
        List<Future<GraphStep>> futures = new ArrayList<>(batch.size());
        for (Integer index : batch) {
            PlanStep step = plan.get(index);
            futures.add(executor.submit(() -> executeStep(round, index, step, ctx)));
        }

        List<GraphStep> batchResults = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            int index = batch.get(i);
            PlanStep step = plan.get(index);
            try {
                // 必须带超时：下游工具卡住时裸 get() 会无限期占着请求线程。
                // 超时后按"该步骤失败"处理，让图继续走 evaluate，而不是把整轮对话拖死。
                batchResults.add(futures.get(i).get(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                log.warn("[Graph] step {} 超时（{}ms），标记为失败", index, STEP_TIMEOUT_MS);
                // 取消仍在跑的任务，避免它在后台继续占用线程
                futures.get(i).cancel(true);
                batchResults.add(GraphStep.builder()
                        .round(round).index(index).tool(step.getTool())
                        .optional(step.isOptional())
                        .success(false).output("执行超时（" + STEP_TIMEOUT_MS + "ms）")
                        .build());
            } catch (Exception e) {
                log.warn("[Graph] step {} failed: {}", index, e.getMessage());
                batchResults.add(GraphStep.builder()
                        .round(round).index(index).tool(step.getTool())
                        .optional(step.isOptional())
                        .success(false).output("执行异常：" + e.getMessage())
                        .build());
            }
        }
        batchResults.sort(Comparator.comparingInt(GraphStep::getIndex));
        steps.addAll(batchResults);
    }

    private GraphStep executeStep(int round, int index, PlanStep step, GraphContext ctx) {
        Map<String, Object> arguments = step.getArguments() == null ? Map.of() : step.getArguments();

        // 循环护栏：超出预算就不再执行，把原因交回给模型
        if (!ctx.guard().allowToolCall(step.getTool(), arguments)) {
            return GraphStep.builder()
                    .round(round).index(index).tool(step.getTool())
                    .reason(step.getReason()).optional(step.isOptional())
                    .success(false).output(ctx.guard().getStopReason())
                    .build();
        }

        // 身份从上下文注入，绝不取自模型给的 arguments——模型不知道真实用户是谁，只能编
        ToolResult result = toolRegistry.execute(new ToolCall(
                "graph_" + round + "_" + index, step.getTool(), arguments, ctx.approved(), ctx.userId()));

        String output = result.isSuccess()
                ? String.valueOf(result.getOutput())
                : "工具执行失败：" + result.getErrorMessage();

        // 调用轨迹带 rawData（可能是任意业务 DTO），放在上下文里而非图状态，
        // 避免图保存快照时序列化失败
        ctx.executions().add(ToolExecution.builder()
                .tool(step.getTool())
                .input(String.valueOf(arguments))
                .output(output)
                .success(result.isSuccess())
                .rawData(result.getRawData())
                .build());

        return GraphStep.builder()
                .round(round).index(index).tool(step.getTool())
                .reason(step.getReason()).optional(step.isOptional())
                .success(result.isSuccess()).output(output)
                .build();
    }

    private List<PlanStep> replan(GraphContext ctx, List<GraphStep> steps) {
        StringBuilder context = new StringBuilder();
        context.append("用户请求：").append(ctx.message()).append("\n\n已执行过的步骤：\n");
        for (GraphStep step : steps) {
            context.append("- ").append(step.getTool())
                    .append(" → ").append(step.isSuccess() ? "成功" : "失败")
                    .append("：").append(abbreviate(step.getOutput())).append("\n");
        }
        context.append("\n请基于以上信息重新给出可执行计划，只包含尚未完成的部分；无法完成则返回 []。")
                .append("\n\n已知背景：\n").append(ctx.systemPrompt());

        return llmProvider.plan(context.toString(), toolRegistry.listDefinitions(), ctx.systemPrompt());
    }

    private String converse(GraphContext ctx) {
        List<ChatMessage> messages = new ArrayList<>();
        if (!ctx.systemPrompt().isEmpty()) {
            messages.add(ChatMessage.builder().role(ChatMessage.Role.SYSTEM).content(ctx.systemPrompt()).build());
        }
        if (!ctx.conversation().isEmpty()) {
            messages.addAll(ctx.conversation());
        } else {
            messages.add(ChatMessage.builder().role(ChatMessage.Role.USER).content(ctx.message()).build());
        }
        return call(ctx, messages);
    }

    private String synthesize(GraphContext ctx, List<GraphStep> steps) {
        StringBuilder observations = new StringBuilder();
        for (GraphStep step : steps) {
            observations.append("【").append(step.getTool()).append("】\n")
                    .append(step.getOutput()).append("\n\n");
        }

        List<ChatMessage> messages = new ArrayList<>();
        if (!ctx.systemPrompt().isEmpty()) {
            messages.add(ChatMessage.builder().role(ChatMessage.Role.SYSTEM).content(ctx.systemPrompt()).build());
        }
        messages.add(ChatMessage.builder().role(ChatMessage.Role.SYSTEM)
                .content("下面是刚查到的真实数据，请基于它用自然、简洁的中文回答用户，不要编造数据。")
                .build());
        messages.add(ChatMessage.builder().role(ChatMessage.Role.USER)
                .content("用户问：" + ctx.message() + "\n\n查询结果：\n" + observations)
                .build());
        return call(ctx, messages);
    }

    private String call(GraphContext ctx, List<ChatMessage> messages) {
        // 循环护栏、高危确认与调用者身份随工具调用下发，工具循环据此把关。
        // 用可变 Map 而非 Map.of：Map.of 不接受 null，身份缺失时会在构造处直接抛 NPE。
        Map<String, Object> loopContext = new HashMap<>();
        loopContext.put(ToolContextKeys.LOOP_GUARD, ctx.guard());
        loopContext.put(ToolContextKeys.APPROVED, ctx.approved());
        if (ctx.userId() != null) {
            loopContext.put(ToolContextKeys.USER_ID, ctx.userId());
        }
        try {
            if (ctx.onChunk() == null) {
                LLMResponse response = llmProvider.chatWithTools(messages, llmConfig, loopContext);
                // 节点内的 ReAct 工具调用轨迹同样要回收，否则前端只能看到计划内那部分
                if (response.getToolExecutions() != null) {
                    ctx.executions().addAll(response.getToolExecutions());
                }
                String content = response.getContent();
                return content == null || content.isBlank() ? "抱歉，我没能完成这个请求。" : content;
            }
            StringBuilder accumulated = new StringBuilder();
            llmProvider.chatStreamWithTools(messages, llmConfig, loopContext, chunk -> {
                accumulated.append(chunk);
                ctx.onChunk().accept(chunk);
            });
            return accumulated.toString();
        } catch (Exception e) {
            log.error("[Graph] answer generation failed", e);
            return "抱歉，智能助手暂时不可用，请稍后再试。";
        }
    }

    // ==================== 辅助 ====================

    private Map<String, Object> updates(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private List<PlanStep> filterRegistered(List<PlanStep> plan) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        List<PlanStep> valid = plan.stream()
                .filter(step -> toolRegistry.get(step.getTool()).isPresent())
                .toList();
        if (valid.size() != plan.size()) {
            log.warn("[Graph] dropped {} step(s) referencing unknown tools", plan.size() - valid.size());
        }
        return valid;
    }

    private boolean requiresConfirmation(PlanStep step) {
        return toolRegistry.get(step.getTool())
                .map(tool -> tool.getDefinition().isRequiresConfirmation())
                .orElse(false);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    // ==================== 状态与结果 ====================

    /**
     * 请求级上下文：由节点闭包捕获，<b>不进入图状态</b>。
     * <p>
     * 图状态要求可序列化，而这里放的都是活对象或不可序列化的领域对象：
     * 流式回调、循环护栏、对话历史，以及工具返回的原始数据
     * （{@code rawData} 可能是任意业务 DTO，塞进状态会在保存快照时抛
     * {@code NotSerializableException}）。
     */
    private record GraphContext(String userId, String message, String systemPrompt, List<ChatMessage> conversation,
                                boolean approved, LoopGuard guard, Consumer<String> onChunk,
                                List<ToolExecution> executions) {

        static GraphContext of(String userId, String message, String systemPrompt, List<ChatMessage> conversation,
                               boolean approved, LoopGuard guard, Consumer<String> onChunk) {
            return new GraphContext(userId, message, systemPrompt, conversation, approved, guard, onChunk,
                    Collections.synchronizedList(new ArrayList<>()));
        }
    }

    /** 图状态：只承载可序列化的执行结果，节点返回的 Map 会合并进来。 */
    public static class GraphState extends AgentState {
        public GraphState(Map<String, Object> init) {
            super(init);
        }

        /** 带默认值的取值：AgentState 的 value(key, default) 已弃用，这里统一收口。 */
        @SuppressWarnings("unchecked")
        public <T> T get(String key, T defaultValue) {
            return (T) value(key).orElse(defaultValue);
        }
    }

    @Data
    @Builder
    public static class GraphStep implements java.io.Serializable {
        private int round;
        private int index;
        private String tool;
        private String reason;
        private String output;
        private boolean success;
        private boolean optional;
    }

    @Data
    @Builder
    public static class GraphResult {
        private String answer;
        private List<PlanStep> plan;
        private List<GraphStep> steps;
        private List<ToolExecution> toolExecutions;
        /** 非空表示图被中断，等待用户确认这些高危操作 */
        private List<String> pendingApproval;
        private int planRounds;
        /** 本次请求的循环消耗摘要，用于可观测 */
        private String loops;
    }
}
