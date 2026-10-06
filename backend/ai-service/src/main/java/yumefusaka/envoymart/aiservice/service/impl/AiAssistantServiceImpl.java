package yumefusaka.envoymart.aiservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.agent.core.Agent;
import yumefusaka.envoymart.agent.core.AgentCancelledException;
import yumefusaka.envoymart.agent.llm.TokenLedger;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.agent.rag.ConflictReporter;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.aiservice.llm.ModelPricing;
import yumefusaka.envoymart.aiservice.memory.ChatHistoryStore;
import yumefusaka.envoymart.aiservice.memory.ChatIdempotencyStore;
import yumefusaka.envoymart.aiservice.model.ChatRequest;
import yumefusaka.envoymart.aiservice.model.ChatResponse;
import yumefusaka.envoymart.aiservice.model.KnowledgeSnippet;
import yumefusaka.envoymart.contract.ProductSummary;
import yumefusaka.envoymart.aiservice.model.ToolCallResponse;
import yumefusaka.envoymart.aiservice.service.AiAssistantService;
import yumefusaka.envoymart.common.web.RequestId;

import java.util.List;
import java.util.Objects;

/**
 * AI 聊天服务实现 —— 委托给自研 Agent 系统。
 * <p>
 * 推理与工具编排由 agent-core 负责，模型接入由 LangChain4j 负责；
 * 这里只做「领域结果 → 对外 DTO」的适配。
 */
@Slf4j
@Service
public class AiAssistantServiceImpl implements AiAssistantService {

    private final Agent agent;
    private final ModelPricing pricing;
    private final ChatHistoryStore history;
    private final ChatIdempotencyStore idempotency;

    public AiAssistantServiceImpl(Agent agent, ModelPricing pricing, ChatHistoryStore history,
                                 ChatIdempotencyStore idempotency) {
        this.agent = agent;
        this.pricing = pricing;
        this.history = history;
        this.idempotency = idempotency;
    }

    /**
     * 开账 → 跑 → 结账，三处必须在同一个方法里。
     * <p>
     * 账本靠线程绑定传递（理由见 {@code TokenLedger}），所以<b>开启它的地方必须包住
     * 整轮的执行</b>：包在 Agent 里就漏掉检索的向量化，包在控制器里则连"这一轮什么时候
     * 算结束"都要控制器来判断。这里是唯一同时满足两头的边界——进来是一次提问，
     * 出去是一份完整回答。
     */
    @Override
    public ChatResponse chat(String userId, ChatRequest request) {
        log.info("[AiService] chat userId={} sessionId={} msg={}",
                userId, request.getSessionId(), request.getMessage());

        String requestId = RequestId.current();
        // 同一个请求号在窗口内重复到达 = 同一次提问被送了两遍（双击、超时重试、代理重放），
        // 直接回上一次的结果，不再跑一遍 Agent。判据只能是请求号而不是消息内容：
        // 用户过一会儿想再问一遍同样的话，那是一次新的提问，必须允许
        if (requestId != null && !idempotency.tryAcquire(userId, requestId)) {
            var previous = idempotency.previous(userId, requestId, ChatResponse.class);
            if (previous.isPresent()) {
                log.info("[AiService] 幂等命中，复用上一次结果 requestId={}", requestId);
                return previous.get();
            }
            // 占位在、结果还没写完（上一次还在执行中）。这不重跑，也不编一个假回答——
            // 如实告诉调用方「同一请求正在处理」，让前端等或换一次新请求
            throw new IllegalStateException("相同请求正在处理中，请勿重复提交");
        }

        try (TokenLedger.Scope ledger = TokenLedger.begin()) {
            ChatResponse response = toChatResponse(request, agent.chat(
                    userId, request.getSessionId(), request.getMessage(), request.getApprovalToken()), ledger);
            recordTurn(userId, request, response);
            idempotency.complete(userId, requestId, response);
            return response;
        }
    }

    @Override
    public ChatResponse chatStream(String userId, ChatRequest request, java.util.function.Consumer<String> onChunk,
                                   ToolProgressListener progress) {
        log.info("[AiService] chatStream userId={} sessionId={} msg={}",
                userId, request.getSessionId(), request.getMessage());

        String requestId = RequestId.current();
        // 流式这条路的幂等**只挡「同一请求号正在处理中」的并发重放，不复用上次结果**。
        // 与非流式（chat）的差别不是疏忽，是两条路的交付形态不同：
        //   - 非流式返回一个完整对象，复用它是无损的；
        //   - 流式的价值全在「逐块送达」这个过程里。若把上一次的结果在这里整块回吐，
        //     调用方拿到的虽是一份正确内容，却失去了一次流式交互应有的形态——
        //     界面会表现为「这次没有逐字输出」，而它无从区分这与「模型这次答得快」。
        // 所以这里的正确行为是**拒绝**（让重复的那次请求失败，前端按自己的重试策略决定），
        // 而不是伪造一次假的流式。真正的防重复由下面这行 tryAcquire 保证：
        // 占位成功才往下跑，Agent 与工具只执行一次。
        if (requestId != null && !idempotency.tryAcquire(userId, requestId)) {
            log.info("[AiService] 流式幂等命中，拒绝同一请求号的并发重放 requestId={}", requestId);
            throw new IllegalStateException("相同请求正在处理中，请勿重复提交");
        }

        try (TokenLedger.Scope ledger = TokenLedger.begin()) {
            // 旁录一份已交付文本。正常收尾时它与 response.getReply() 相同（甚至更短——
            // done 帧要过后置校验，剔除过的句子不出现在 reply 里）；被取消时它是唯一的
            // 「用户看到过什么」的记录。历史必须与屏幕一致：用户按停止后回看，
            // 看到一段自己从未见过的完整答案是更坏的谎
            StringBuilder delivered = new StringBuilder();
            ToolProgressListener effective = ToolProgressListener.orNoop(progress);
            try {
                ChatResponse response = toChatResponse(request, agent.chatStream(
                        userId, request.getSessionId(), request.getMessage(), request.getApprovalToken(),
                        chunk -> {
                            // 只把「交付得出去」的分片计入。断开之后模型还在推的分片，
                            // 控制器已不再往那条连接上写——把它们算进已交付，历史里
                            // 就会多出用户从没见过的结尾。与控制器同一判据，边界误差
                            // 最多一片（旗标翻转与分片到达之间的那一片）
                            if (!effective.cancelled()) {
                                delivered.append(chunk);
                            }
                            onChunk.accept(chunk);
                        },
                        effective), ledger);
                recordTurn(userId, request, response);
                // 正常收尾后把占位换成结果。**必须替换**，不能留在 "PENDING"：
                // 占位在窗口内会让同一个请求号一律被拒，而「这一轮已经成功交付完了」之后
                // 再来的重复请求（前端收完 done 帧又因网络抖动重发一次）应当被认成已完成、
                // 直接复用结果，而不是被判成「还在处理中」。非流式那条路同一处理。
                idempotency.complete(userId, requestId, response);
                return response;
            } catch (AgentCancelledException e) {
                log.info("[AiService] 本轮已取消，按已交付的 {} 字落历史 userId={} sessionId={}",
                        delivered.length(), userId, request.getSessionId());
                recordTurn(userId, request, ChatResponse.builder()
                        .usage(usageOf(ledger))
                        .requestId(RequestId.current())
                        .sessionId(request.getSessionId())
                        .reply(delivered.toString())
                        .build());
                throw e;
            }
        }
    }

    /**
     * 一轮对话结束后落历史 —— 侧栏的「切换会话能看到原话」靠它。
     * <p>
     * 记录点在服务实现层而不是控制器：非流式、SSE 两条路都从这里出去，
     * 放在这里只有一处；而这两条路各有自己的控制器方法，放在控制器要写两遍
     * （写两遍的代价是将来加第三条路时漏掉一处，历史从此静默不完整）。
     * <p>
     * 记的是<b>校验之后</b>的最终答复：带引用的句子被剔除过的那一版才是用户看到的，
     * 历史要和它对上。
     * <p>
     * <b>确认令牌不落历史。</b>它是一张写明「执行哪几次调用」的签名凭证，靠会话归属和
     * 十分钟有效期兜底；而历史在 Redis 里存得更久，且每次拉会话都会随 `response` 原样回给
     * 客户端。界面本来就不恢复确认卡片（过期卡片点不动），把它留在历史里就是一份
     * 白白多躺十天的凭证。卡片文案照旧保留：用户回看时该看到「当时问过他要不要确认」。
     */
    private void recordTurn(String userId, ChatRequest request, ChatResponse response) {
        // 重新生成：用户重问的是「刚才那条」，会话里不该出现第二条用户消息——
        // 就地改写最后一条助手答复。没有这个分支，点一次重新生成历史就多一对
        // 重复的问答，点三次侧栏里就挂着一串一模一样的提问
        if (request.isRegenerate()) {
            history.recordAnswer(userId, request.getSessionId(),
                    response.getReply(), response.toBuilder().approvalToken(null).build());
            return;
        }
        history.recordTurn(userId, request.getSessionId(), request.getMessage(),
                response.getReply(), response.toBuilder().approvalToken(null).build());
    }

    private ChatResponse toChatResponse(ChatRequest request, Agent.AgentResponse agentResp,
                                        TokenLedger.Scope ledger) {
        List<ToolExecution> executions = agentResp.getToolExecutions() == null
                ? List.of() : agentResp.getToolExecutions();

        return ChatResponse.builder()
                .usage(usageOf(ledger))
                .requestId(RequestId.current())
                .sessionId(request.getSessionId())
                .reply(agentResp.getReply())
                .retrievalQuery(agentResp.getRetrievalQuery())
                .expansion(expandView(agentResp.getExpansion()))
                .memoryTrace(memoryTraceView(agentResp.getMemoryTrace()))
                .knowledge(convertKnowledge(agentResp.getKnowledge()))
                .toolCalls(executions.stream().map(this::toToolCall).toList())
                .recommendedProducts(extractProducts(executions))
                .pendingActions(agentResp.getPendingActions())
                .approvalToken(agentResp.getApprovalToken())
                .evidenceLevel(agentResp.getEvidenceLevel())
                // 阶段用 name() 而不是 toString()：枚举名是稳定契约，toString 可能被人
                // 重写成中文标签，那时前端的判断会静默失效
                .stage(agentResp.getStage() == null ? null : agentResp.getStage().name())
                // 任务状态按「对外字段名」投影，而不是把 record 原样丢出去：
                // 契约字段名是这条链的稳定面，直接序列化 record 会让一次字段重命名
                // 静默改掉前端读到的东西（Jackson 不会报错，只会换个 key）
                .taskState(taskStateOf(agentResp.getTaskState()))
                .unsupportedClaims(agentResp.getUnsupportedClaims())
                .unsupportedStripped(agentResp.isUnsupportedStripped())
                .ungrounded(agentResp.isUngrounded())
                .factMismatches(agentResp.getFactMismatches())
                .factStripped(agentResp.isFactStripped())
                .conflicts(convertConflicts(agentResp.getConflicts()))
                .build();
    }

    /**
     * 任务状态投影 —— 把 agent-core 的 {@link TaskState} 翻成对外的下划线字段名。
     * <p>
     * <b>为什么不直接把 record 交给 Jackson。</b>record 的组件名就是 JSON key，
     * 一次字段重命名（哪怕只是内部整理）会静默改掉前端读到的 key——Jackson 不会报错，
     * 前端只是某块显示空白。这里手工列出契约字段，改名会变成一次编译错误，
     * 而不是一次线上静默失效。
     * <p>
     * 字段名与 objective/前端约定一致：{@code task_id}、{@code core_intent}、
     * {@code current_subtask}、{@code pending_tools}、{@code completed_steps}、
     * {@code context_snapshot}、{@code stage}、{@code round}。
     * <p>
     * 用 {@link java.util.LinkedHashMap} 而不是 {@code Map.of}：后者不接受 null 值，
     * 而 {@code core_intent}/{@code current_subtask} 在 ReAct 路径下就是 null。
     */
    private java.util.Map<String, Object> taskStateOf(yumefusaka.envoymart.agent.core.task.TaskState state) {
        if (state == null) {
            return null;
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("task_id", state.taskId());
        out.put("user_id", state.userId());
        out.put("session_id", state.sessionId());
        out.put("core_intent", state.coreIntent());
        out.put("current_subtask", state.currentSubtask());
        out.put("pending_tools", state.pendingTools());
        out.put("completed_steps", state.completedSteps());
        out.put("context_snapshot", state.contextSnapshot());
        out.put("stage", state.stage() == null ? null : state.stage().name());
        out.put("round", state.round());
        out.put("updated_at", state.updatedAtEpochMs());
        return out;
    }
    /**
     * 扩写视图的搬运 —— 空产出统一归到 {@link ChatResponse.ExpansionView#textOnly()}。
     * <p>
     * 不把 agent-core 的 record 直接序列化出去，理由与 {@code taskStateOf} 相同：
     * 内部字段名不是对外契约，一次内部整理不该静默改掉前端读到的 key。
     */
    private ChatResponse.ExpansionView expandView(
            yumefusaka.envoymart.agent.rag.QueryExpansions expansions) {
        if (expansions == null || expansions.isEmpty()) {
            return ChatResponse.ExpansionView.textOnly();
        }
        return new ChatResponse.ExpansionView(
                expansions.hypothetical(), expansions.angles(), true);
    }

    /**
     * 记忆注入事实的对外翻译，理由与 {@link #expandView} 相同：内部字段名不是对外契约。
     * <p>
     * <b>没有记录时返回「空读数」而不是 null。</b>null 会让前端无法区分
     * 「这一轮查过、没有相关记忆」（新用户冷启动，正常）与「这一轮没走记忆这条路」
     * （链路问题）。只有明确两类情况真的不可区分时，才该退到 null。
     */
    private ChatResponse.MemoryTraceView memoryTraceView(
            yumefusaka.envoymart.agent.core.Agent.MemoryTrace trace) {
        if (trace == null) {
            return ChatResponse.MemoryTraceView.empty();
        }
        return new ChatResponse.MemoryTraceView(
                trace.profileSlots(), trace.recalled(), trace.byType(), trace.itemIds(),
                trace.observationSources());
    }

    /**
     * 结账并翻译成对外 DTO。
     * <p>
     * 一次模型调用都没发生时返回 {@code null} 而不是一个全零的用量：前端据此整块不渲染。
     * 显示「本轮 0 tokens」比不显示更糟——它看起来像统计坏了，而真相是这一轮压根没花钱。
     */
    private ChatResponse.Usage usageOf(TokenLedger.Scope ledger) {
        TokenLedger.Snapshot snapshot = ledger.snapshot();
        if (snapshot.isEmpty()) {
            return null;
        }
        List<String> unpriced = pricing.unpricedModels(snapshot.models());
        // 全部模型都没配单价时不给金额：0.0 会被读成「免费」，而真相是「不知道」
        Double cost = unpriced.size() == snapshot.models().size()
                ? null : pricing.estimateCny(snapshot.models());
        log.info("[AiService] 本轮用量 promptTokens={} completionTokens={} totalTokens={} costCny={} 未计价={}",
                snapshot.promptTokens(), snapshot.completionTokens(), snapshot.totalTokens(),
                cost == null ? "-" : String.format("%.4f", cost), unpriced);
        return new ChatResponse.Usage(
                snapshot.promptTokens(), snapshot.completionTokens(), snapshot.totalTokens(),
                cost, unpriced,
                snapshot.models().stream()
                        .map(model -> new ChatResponse.ModelUsage(
                                model.model(), model.promptTokens(), model.completionTokens()))
                        .toList());
    }

    /** 冲突的结构化搬运。{@code refs} 原样透传——它就是回答里 {@code [n]} 的 n，前端据此生成跳转 */
    private List<ChatResponse.Conflict> convertConflicts(List<ConflictReporter.Conflict> conflicts) {
        if (conflicts == null) {
            return List.of();
        }
        return conflicts.stream()
                .map(c -> new ChatResponse.Conflict(c.refs(), c.detail(), c.resolved()))
                .toList();
    }

    private ToolCallResponse toToolCall(ToolExecution execution) {
        return ToolCallResponse.builder()
                .tool(execution.getTool())
                .input(execution.getInput())
                .output(execution.getOutput())
                .success(execution.isSuccess())
                .noData(execution.isNoData())
                .latencyMs(execution.getLatencyMs())
                .facts(execution.getFacts())
                .build();
    }

    /** 从工具执行结果里抽取商品卡片数据（商品搜索工具的 rawData）。 */
    private List<ProductSummary> extractProducts(List<ToolExecution> executions) {
        return executions.stream()
                .filter(ToolExecution::isSuccess)
                .map(ToolExecution::getRawData)
                .filter(Objects::nonNull)
                .filter(List.class::isInstance)
                .flatMap(data -> ((List<?>) data).stream())
                .filter(ProductSummary.class::isInstance)
                .map(ProductSummary.class::cast)
                .distinct()
                .toList();
    }

    /**
     * 切片 → 引用片段，<b>原样透传溯源字段</b>。
     * <p>
     * 这里曾经只挑了 content，标题填的是 {@code docId}、scope 硬编码 "rag" ——
     * 于是模型在正文里标注的 {@code [1]} 在前端渲染成「[1] doc_12」，
     * 用户看不到文档名、看不到出自哪一节，所谓「可追溯」到这一步就断了。
     * 字段在这里只做搬运，不做取舍：丢掉任何一个都要问一句「用户还核得对吗」。
     */
    private List<KnowledgeSnippet> convertKnowledge(List<DocumentChunk> chunks) {
        if (chunks == null) {
            return List.of();
        }
        return chunks.stream()
                .map(c -> KnowledgeSnippet.builder()
                        .chunkId(c.getChunkId())
                        .docId(c.getDocId())
                        .title(c.getTitle())
                        .scope(c.getScope())
                        .source(c.getSource())
                        .version(c.getVersion())
                        .position(c.getPosition())
                        .charOffset(c.getCharOffset())
                        .score(c.getScore())
                        .reranked(c.getReranked())
                        .content(c.getContent())
                        .build())
                .toList();
    }
}
