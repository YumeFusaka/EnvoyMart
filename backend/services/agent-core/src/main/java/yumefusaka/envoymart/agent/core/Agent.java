package yumefusaka.envoymart.agent.core;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.memory.*;
import yumefusaka.envoymart.agent.rag.RAGEngine;
import yumefusaka.envoymart.agent.skill.SkillContext;
import yumefusaka.envoymart.agent.skill.SkillRegistry;
import yumefusaka.envoymart.agent.skill.WorkflowEngine;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.List;
import java.util.UUID;

/**
 * Agent —— 自研 Agent 系统的统一入口门面。
 * <p>
 * 整合 ReAct / PAE、记忆、RAG、工具、Skill 等全部子系统，
 * 提供开箱即用的 chat() 接口。
 */
@Slf4j
public class Agent {

    private final Config config;
    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final WorkflowEngine workflowEngine;
    private final Memory shortTermMemory;
    private final Memory longTermMemory;
    private final RAGEngine ragEngine;
    private final ContextManager contextManager;
    private final ReActEngine reActEngine;
    private final PAEEngine paeEngine;
    private final LLMProvider llmProvider;

    public Agent(Config config,
                 ToolRegistry toolRegistry,
                 SkillRegistry skillRegistry,
                 WorkflowEngine workflowEngine,
                 Memory shortTermMemory,
                 Memory longTermMemory,
                 RAGEngine ragEngine,
                 ContextManager contextManager,
                 ReActEngine reActEngine,
                 PAEEngine paeEngine,
                 LLMProvider llmProvider) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.workflowEngine = workflowEngine;
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.ragEngine = ragEngine;
        this.contextManager = contextManager;
        this.reActEngine = reActEngine;
        this.paeEngine = paeEngine;
        this.llmProvider = llmProvider;
    }

    /**
     * 统一聊天入口 —— 自动选择执行策略：
     * - 匹配到 Skill → 按 Workflow 编排执行
     * - 复杂任务（含多个工具依赖）→ PAE
     * - 一般对话 → ReAct
     */
    public AgentResponse chat(String userId, String sessionId, String message) {
        log.info("[Agent] chat userId={} sessionId={}", userId, sessionId);

        // 1. 记录用户消息到短期记忆
        shortTermMemory.add(MemoryItem.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .content("user: " + message)
                .type(MemoryItem.Type.MESSAGE)
                .build());

        // 2. RAG 检索相关知识
        var knowledge = ragEngine.retrieve(message, config.getRagTopK());
        String systemPrompt = buildSystemPrompt(knowledge);

        // 3. 检查是否有匹配的 Skill
        var skillOpt = skillRegistry.route(message);

        AgentResponse response;

        if (skillOpt.isPresent()) {
            // Skill 模式 —— 按工作流执行
            log.debug("[Agent] routed to skill: {}", skillOpt.get().getName());
            var context = SkillContext.builder()
                    .userId(userId).sessionId(sessionId).userMessage(message)
                    .shortTermMemory(shortTermMemory).longTermMemory(longTermMemory)
                    .toolRegistry(toolRegistry).ragEngine(ragEngine)
                    .build();
            // 简化处理：直接执行 skill 而非完整 workflow
            var result = skillOpt.get().execute(context);
            response = AgentResponse.builder()
                    .reply(result.getOutput())
                    .source("skill")
                    .knowledge(knowledge)
                    .build();
        } else if (isComplexTask(message)) {
            // 复杂任务 → PAE
            log.debug("[Agent] using PAE engine");
            var paeResult = paeEngine.execute(message, List.of(
                    ChatMessage.builder().role(ChatMessage.Role.USER).content(message).build()));
            response = AgentResponse.builder()
                    .reply(paeResult.getFinalAnswer())
                    .source("pae")
                    .knowledge(knowledge)
                    .build();
        } else {
            // 一般对话 → ReAct
            log.debug("[Agent] using ReAct engine");
            var recentMemory = shortTermMemory.recent(sessionId, config.getMemoryWindow());
            var conversation = recentMemory.stream()
                    .map(m -> ChatMessage.builder()
                            .role(m.getContent().startsWith("user:") ? ChatMessage.Role.USER : ChatMessage.Role.ASSISTANT)
                            .content(m.getContent().replaceAll("^(user:|assistant:)", "").trim())
                            .build())
                    .toList();

            var reActResult = reActEngine.execute(systemPrompt, conversation);
            response = AgentResponse.builder()
                    .reply(reActResult.getFinalAnswer())
                    .source("react")
                    .knowledge(knowledge)
                    .build();
        }

        // 4. 记录回复到短期记忆
        shortTermMemory.add(MemoryItem.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .content("assistant: " + response.getReply())
                .type(MemoryItem.Type.MESSAGE)
                .build());

        return response;
    }

    private String buildSystemPrompt(List<yumefusaka.envoymart.agent.rag.DocumentChunk> knowledge) {
        if (knowledge.isEmpty()) return config.getDefaultSystemPrompt();
        StringBuilder sb = new StringBuilder(config.getDefaultSystemPrompt());
        sb.append("\n\n相关知识：\n");
        for (int i = 0; i < knowledge.size(); i++) {
            sb.append(i + 1).append(". ").append(knowledge.get(i).getContent()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 通过快速 LLM 判断任务类型。
     * 无法判定时回退到关键词启发式。
     */
    private boolean isComplexTask(String message) {
        try {
            List<ChatMessage> classifyMessages = List.of(
                    ChatMessage.builder().role(ChatMessage.Role.SYSTEM)
                            .content("判断用户请求是否需要多步操作（如比价、退换货、查物流后下单），只需回复 yes 或 no。").build(),
                    ChatMessage.builder().role(ChatMessage.Role.USER).content(message).build()
            );
            LLMResponse resp = llmProvider.chat(classifyMessages, llmConfig);
            if (resp.getContent() != null && resp.getContent().toLowerCase().contains("yes")) {
                return true;
            }
        } catch (Exception e) {
            log.warn("[Agent] LLM classification failed, fallback to keyword heuristic");
        }
        // Fallback：关键词启发式
        long toolKeywords = message.chars().filter(c -> "买卖下单物流退换比价".indexOf(c) >= 0).count();
        return toolKeywords >= 2;
    }

    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public SkillRegistry getSkillRegistry() { return skillRegistry; }
    public Memory getShortTermMemory() { return shortTermMemory; }
    public Memory getLongTermMemory() { return longTermMemory; }
    public RAGEngine getRagEngine() { return ragEngine; }

    @Data
    @Builder
    public static class AgentResponse {
        private String reply;
        private String source;   // react / pae / skill
        private List<yumefusaka.envoymart.agent.rag.DocumentChunk> knowledge;
    }

    @Data
    @Builder
    public static class Config {
        @Builder.Default private int memoryWindow = 16;
        @Builder.Default private int ragTopK = 3;
        @Builder.Default private String defaultSystemPrompt = "你是一个智能电商助手，帮助用户选购商品、查询订单、解答售后问题。";
        @Builder.Default private AgentMode mode = AgentMode.AUTO;

        public enum AgentMode {
            REACT,
            PAE,
            AUTO
        }
    }
}
