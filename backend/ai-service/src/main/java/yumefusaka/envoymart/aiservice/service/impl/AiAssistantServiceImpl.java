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

    public AiAssistantServiceImpl(Agent agent, ModelPricing pricing, ChatHistoryStore history) {
        this.agent = agent;
        this.pricing = pricing;
        this.history = history;
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

        try (TokenLedger.Scope ledger = TokenLedger.begin()) {
            ChatResponse response = toChatResponse(request, agent.chat(
                    userId, request.getSessionId(), request.getMessage(), request.getApprovalToken()), ledger);
            recordTurn(userId, request, response);
            return response;
        }
    }

    @Override
    public ChatResponse chatStream(String userId, ChatRequest request, java.util.function.Consumer<String> onChunk,
                                   ToolProgressListener progress) {
        log.info("[AiService] chatStream userId={} sessionId={} msg={}",
                userId, request.getSessionId(), request.getMessage());

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
                .knowledge(convertKnowledge(agentResp.getKnowledge()))
                .toolCalls(executions.stream().map(this::toToolCall).toList())
                .recommendedProducts(extractProducts(executions))
                .pendingActions(agentResp.getPendingActions())
                .approvalToken(agentResp.getApprovalToken())
                .evidenceLevel(agentResp.getEvidenceLevel())
                .unsupportedClaims(agentResp.getUnsupportedClaims())
                .unsupportedStripped(agentResp.isUnsupportedStripped())
                .ungrounded(agentResp.isUngrounded())
                .factMismatches(agentResp.getFactMismatches())
                .factStripped(agentResp.isFactStripped())
                .conflicts(convertConflicts(agentResp.getConflicts()))
                .build();
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
                .map(c -> new ChatResponse.Conflict(c.refs(), c.detail()))
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
