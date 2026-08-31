package yumefusaka.envoymart.agent.core;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.memory.MemoryConsolidator;
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
    private final LLMConfig llmConfig;
    private final MemoryConsolidator consolidator;

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
                 LLMProvider llmProvider,
                 LLMConfig llmConfig,
                 MemoryConsolidator consolidator) {
        this.consolidator = consolidator;
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
        this.llmConfig = llmConfig;
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

        // 2. RAG 检索相关知识 + 长期记忆语义召回
        var knowledge = ragEngine.retrieve(message, config.getRagTopK());
        var memories = longTermMemory.recall(message, config.getLongTermRecallTopK());
        String systemPrompt = buildSystemPrompt(knowledge, memories);

        // 3. 检查是否有匹配的 Skill
        var skillOpt = skillRegistry.route(message);

        AgentResponse response;
        try {
            response = route(userId, sessionId, message, knowledge, systemPrompt, skillOpt);
        } catch (Exception e) {
            // 模型/工具链路异常不应把整个请求打成 500，降级为可读提示
            log.error("[Agent] chat failed, degrade to fallback reply", e);
            response = AgentResponse.builder()
                    .reply("抱歉，智能助手暂时不可用，请稍后再试或换个说法。")
                    .source("fallback")
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

        // 5. 执行后：沉淀长期记忆
        consolidateMemory(sessionId);

        return response;
    }

    /** 按「Skill → PAE → ReAct」优先级选择执行策略。 */
    private AgentResponse route(String userId, String sessionId, String message,
                                List<yumefusaka.envoymart.agent.rag.DocumentChunk> knowledge,
                                String systemPrompt,
                                java.util.Optional<yumefusaka.envoymart.agent.skill.Skill> skillOpt) {
        if (skillOpt.isPresent()) {
            log.debug("[Agent] routed to skill: {}", skillOpt.get().getName());
            var context = SkillContext.builder()
                    .userId(userId).sessionId(sessionId).userMessage(message)
                    .shortTermMemory(shortTermMemory).longTermMemory(longTermMemory)
                    .toolRegistry(toolRegistry).ragEngine(ragEngine)
                    .build();
            var result = skillOpt.get().execute(context);
            return AgentResponse.builder()
                    .reply(result.getOutput())
                    .source("skill")
                    .knowledge(knowledge)
                    .build();
        }

        List<PlanStep> plan = isComplexTask(message) ? planFor(message, systemPrompt) : List.of();
        if (!plan.isEmpty()) {
            // 复杂任务且确实能拆成工具步骤 → PAE
            log.debug("[Agent] using PAE engine, plan={}", plan.stream().map(PlanStep::getTool).toList());
            var paeResult = paeEngine.execute(message, List.of(
                    ChatMessage.builder().role(ChatMessage.Role.USER).content(message).build()),
                    plan, systemPrompt);
            return AgentResponse.builder()
                    .reply(paeResult.getFinalAnswer())
                    .source("pae")
                    .knowledge(knowledge)
                    .toolExecutions(paeResult.getToolExecutions())
                    .build();
        }

        // 一般对话 / 规划落不了地 → ReAct
        log.debug("[Agent] using ReAct engine");
        var recentMemory = shortTermMemory.recent(sessionId, config.getMemoryWindow());
        var conversation = recentMemory.stream()
                .map(m -> ChatMessage.builder()
                        .role(m.getContent().startsWith("user:") ? ChatMessage.Role.USER : ChatMessage.Role.ASSISTANT)
                        .content(m.getContent().replaceAll("^(user:|assistant:)", "").trim())
                        .build())
                .toList();

        var reActResult = reActEngine.execute(systemPrompt, conversation);
        return AgentResponse.builder()
                .reply(reActResult.getFinalAnswer())
                .source("react")
                .knowledge(knowledge)
                .toolExecutions(reActResult.getToolExecutions())
                .build();
    }

    private String buildSystemPrompt(List<yumefusaka.envoymart.agent.rag.DocumentChunk> knowledge,
                                     List<MemoryItem> memories) {
        StringBuilder sb = new StringBuilder(config.getDefaultSystemPrompt());
        if (!knowledge.isEmpty()) {
            sb.append("\n\n相关知识：\n");
            for (int i = 0; i < knowledge.size(); i++) {
                sb.append(i + 1).append(". ").append(knowledge.get(i).getContent()).append("\n");
            }
        }
        if (!memories.isEmpty()) {
            sb.append("\n\n关于该用户你记得：\n");
            for (MemoryItem memory : memories) {
                sb.append("- ").append(memory.getContent()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 把本轮对话中值得长期记住的事实/偏好沉淀到长期记忆。
     * 失败不影响主链路——记忆是增强项，不是必需项。
     */
    private void consolidateMemory(String sessionId) {
        if (consolidator == null || !config.isMemoryConsolidationEnabled()) {
            return;
        }
        try {
            var recent = shortTermMemory.recent(sessionId, config.getMemoryWindow());
            var facts = consolidator.extract(sessionId, recent);
            facts.forEach(longTermMemory::add);
            if (!facts.isEmpty()) {
                log.debug("[Agent] consolidated {} memory item(s)", facts.size());
            }
        } catch (Exception e) {
            log.warn("[Agent] memory consolidation failed: {}", e.getMessage());
        }
    }

    /**
     * 生成可执行的工具计划。
     * 返回空表示没有工具能帮上忙，此时应回落到 ReAct —— 让它基于 RAG 知识直接回答，
     * 而不是把"没有可用工具"当成最终答复。
     */
    private List<PlanStep> planFor(String message, String systemPrompt) {
        var plan = llmProvider.plan(message, toolRegistry.listDefinitions(), systemPrompt);
        return plan == null ? List.of() : plan;
    }

    /**
     * 通过快速 LLM 判断任务类型。
     * 无法判定时回退到关键词启发式。
     */
    private boolean isComplexTask(String message) {
        try {
            List<ChatMessage> classifyMessages = List.of(
                    ChatMessage.builder().role(ChatMessage.Role.SYSTEM)
                            .content("判断用户请求是否必须通过调用业务接口才能完成（如查订单、查物流、搜索商品），"
                                    + "只是咨询规则或闲聊则不需要。只需回复 yes 或 no。").build(),
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
        /** 本轮对话实际发生的工具调用轨迹 */
        private List<yumefusaka.envoymart.agent.llm.ToolExecution> toolExecutions;
    }

    @Data
    @Builder
    public static class Config {
        @Builder.Default private int memoryWindow = 16;
        @Builder.Default private int ragTopK = 3;
        /** 每轮注入的长期记忆条数 */
        @Builder.Default private int longTermRecallTopK = 3;
        /** 是否在每轮结束后抽取事实沉淀到长期记忆（会额外调用一次模型） */
        @Builder.Default private boolean memoryConsolidationEnabled = true;
        @Builder.Default private String defaultSystemPrompt = "你是一个智能电商助手，帮助用户选购商品、查询订单、解答售后问题。";
        @Builder.Default private AgentMode mode = AgentMode.AUTO;

        public enum AgentMode {
            REACT,
            PAE,
            AUTO
        }
    }
}
