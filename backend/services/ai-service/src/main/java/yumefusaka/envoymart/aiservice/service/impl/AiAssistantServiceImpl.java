package yumefusaka.envoymart.aiservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.agent.core.Agent;
import yumefusaka.envoymart.aiservice.model.ChatRequest;
import yumefusaka.envoymart.aiservice.model.ChatResponse;
import yumefusaka.envoymart.aiservice.model.KnowledgeSnippet;
import yumefusaka.envoymart.aiservice.model.ToolCallResponse;
import yumefusaka.envoymart.aiservice.service.AiAssistantService;

import java.util.List;

/**
 * AI 聊天服务实现 —— 委托给自研 Agent 系统。
 * <p>
 * 从 Spring AI 迁移到自研 agent-core 框架，
 * 所有推理、工具调用、RAG 由 Agent 统一编排。
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

        // 委托给自研 Agent 系统
        Agent.AgentResponse agentResp = agent.chat(
                userId, request.getSessionId(), request.getMessage());

        // 适配返回格式（保持对外接口不变）
        return ChatResponse.builder()
                .sessionId(request.getSessionId())
                .reply(agentResp.getReply())
                .knowledge(convertKnowledge(agentResp.getKnowledge()))
                .toolCalls(List.of())
                .recommendedProducts(List.of())
                .build();
    }

    private List<KnowledgeSnippet> convertKnowledge(
            List<yumefusaka.envoymart.agent.rag.DocumentChunk> chunks) {
        if (chunks == null) return List.of();
        return chunks.stream()
                .map(c -> KnowledgeSnippet.builder()
                        .title(c.getDocId())
                        .content(c.getContent())
                        .scope("rag")
                        .build())
                .toList();
    }
}
