package yumefusaka.envoymart.aiservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.agent.core.Agent;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.aiservice.model.ChatRequest;
import yumefusaka.envoymart.aiservice.model.ChatResponse;
import yumefusaka.envoymart.aiservice.model.KnowledgeSnippet;
import yumefusaka.envoymart.aiservice.model.ProductResponse;
import yumefusaka.envoymart.aiservice.model.ToolCallResponse;
import yumefusaka.envoymart.aiservice.service.AiAssistantService;

import java.util.List;
import java.util.Objects;

/**
 * AI 聊天服务实现 —— 委托给自研 Agent 系统。
 * <p>
 * 推理与工具编排由 agent-core 负责，模型接入由 Spring AI 负责；
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

        Agent.AgentResponse agentResp = agent.chat(
                userId, request.getSessionId(), request.getMessage());

        List<ToolExecution> executions = agentResp.getToolExecutions() == null
                ? List.of() : agentResp.getToolExecutions();

        return ChatResponse.builder()
                .sessionId(request.getSessionId())
                .reply(agentResp.getReply())
                .knowledge(convertKnowledge(agentResp.getKnowledge()))
                .toolCalls(executions.stream().map(this::toToolCall).toList())
                .recommendedProducts(extractProducts(executions))
                .build();
    }

    private ToolCallResponse toToolCall(ToolExecution execution) {
        return ToolCallResponse.builder()
                .tool(execution.getTool())
                .input(execution.getInput())
                .output(execution.getOutput())
                .build();
    }

    /** 从工具执行结果里抽取商品卡片数据（商品搜索工具的 rawData）。 */
    private List<ProductResponse> extractProducts(List<ToolExecution> executions) {
        return executions.stream()
                .filter(ToolExecution::isSuccess)
                .map(ToolExecution::getRawData)
                .filter(Objects::nonNull)
                .filter(List.class::isInstance)
                .flatMap(data -> ((List<?>) data).stream())
                .filter(ProductResponse.class::isInstance)
                .map(ProductResponse.class::cast)
                .distinct()
                .toList();
    }

    private List<KnowledgeSnippet> convertKnowledge(List<DocumentChunk> chunks) {
        if (chunks == null) {
            return List.of();
        }
        return chunks.stream()
                .map(c -> KnowledgeSnippet.builder()
                        .title(c.getDocId())
                        .content(c.getContent())
                        .scope("rag")
                        .build())
                .toList();
    }
}
