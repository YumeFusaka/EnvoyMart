package yumefusaka.envoymart.aiservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.agent.core.Agent;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.rag.ConflictReporter;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.aiservice.model.ChatRequest;
import yumefusaka.envoymart.aiservice.model.ChatResponse;
import yumefusaka.envoymart.aiservice.model.KnowledgeSnippet;
import yumefusaka.envoymart.contract.ProductSummary;
import yumefusaka.envoymart.aiservice.model.ToolCallResponse;
import yumefusaka.envoymart.aiservice.service.AiAssistantService;

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

    public AiAssistantServiceImpl(Agent agent) {
        this.agent = agent;
    }

    @Override
    public ChatResponse chat(String userId, ChatRequest request) {
        log.info("[AiService] chat userId={} sessionId={} msg={}",
                userId, request.getSessionId(), request.getMessage());

        return toChatResponse(request, agent.chat(
                userId, request.getSessionId(), request.getMessage(), request.isApproved()));
    }

    @Override
    public ChatResponse chatStream(String userId, ChatRequest request, java.util.function.Consumer<String> onChunk) {
        log.info("[AiService] chatStream userId={} sessionId={} msg={}",
                userId, request.getSessionId(), request.getMessage());

        return toChatResponse(request, agent.chatStream(
                userId, request.getSessionId(), request.getMessage(), request.isApproved(), onChunk));
    }

    private ChatResponse toChatResponse(ChatRequest request, Agent.AgentResponse agentResp) {
        List<ToolExecution> executions = agentResp.getToolExecutions() == null
                ? List.of() : agentResp.getToolExecutions();

        return ChatResponse.builder()
                .sessionId(request.getSessionId())
                .reply(agentResp.getReply())
                .knowledge(convertKnowledge(agentResp.getKnowledge()))
                .toolCalls(executions.stream().map(this::toToolCall).toList())
                .recommendedProducts(extractProducts(executions))
                .pendingActions(agentResp.getPendingActions())
                .evidenceLevel(agentResp.getEvidenceLevel())
                .unsupportedClaims(agentResp.getUnsupportedClaims())
                .unsupportedStripped(agentResp.isUnsupportedStripped())
                .ungrounded(agentResp.isUngrounded())
                .conflicts(convertConflicts(agentResp.getConflicts()))
                .build();
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
