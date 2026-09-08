package yumefusaka.envoymart.agent.core;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.flow.DeterministicFlow;
import yumefusaka.envoymart.agent.flow.FlowContext;
import yumefusaka.envoymart.agent.flow.FlowResult;
import yumefusaka.envoymart.agent.flow.IntentRouter;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.loop.LoopBudget;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.memory.Memory;
import yumefusaka.envoymart.agent.memory.MemoryConsolidator;
import yumefusaka.envoymart.agent.memory.MemoryItem;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.RAGEngine;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Agent —— 统一入口。
 * <p>
 * 整体是<b>两层</b>结构，不是几种并列的「推理模式」：
 * <pre>
 *  ① 入口守卫：能不能确定？能确定就走确定性流程（业务判定零 LLM）
 *        ↓ 不能确定
 *  ② 执行图：规划 → 执行（并发）→ 评估 → 重规划 → 合成回答
 *        （图中「计划为空」时转为直接对话——ReAct 循环就发生在那里，由框架驱动）
 * </pre>
 * 执行前加载长期记忆与 RAG 知识组装 system prompt；执行后把本轮事实沉淀回长期记忆。
 */
@Slf4j
public class Agent {

    private final Config config;
    private final ToolRegistry toolRegistry;
    private final IntentRouter intentRouter;
    private final AgentGraph agentGraph;
    private final Memory shortTermMemory;
    private final Memory longTermMemory;
    private final RAGEngine ragEngine;
    private final MemoryConsolidator consolidator;

    public Agent(Config config,
                 ToolRegistry toolRegistry,
                 IntentRouter intentRouter,
                 AgentGraph agentGraph,
                 Memory shortTermMemory,
                 Memory longTermMemory,
                 RAGEngine ragEngine,
                 MemoryConsolidator consolidator) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.intentRouter = intentRouter;
        this.agentGraph = agentGraph;
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.ragEngine = ragEngine;
        this.consolidator = consolidator;
    }

    public AgentResponse chat(String userId, String sessionId, String message, boolean approved) {
        return doChat(userId, sessionId, message, approved, null);
    }

    /** 流式变体：最终回答逐块推送；工具编排阶段仍是同步的。 */
    public AgentResponse chatStream(String userId, String sessionId, String message,
                                    boolean approved, Consumer<String> onChunk) {
        return doChat(userId, sessionId, message, approved, onChunk);
    }

    private AgentResponse doChat(String userId, String sessionId, String message,
                                 boolean approved, Consumer<String> onChunk) {
        log.info("[Agent] chat userId={} sessionId={} approved={}", userId, sessionId, approved);

        // 1. 记录用户消息
        shortTermMemory.add(MemoryItem.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .content("user: " + message)
                .type(MemoryItem.Type.MESSAGE)
                .build());

        // 2. RAG 检索 + 长期记忆召回 → system prompt
        List<DocumentChunk> knowledge = ragEngine.retrieve(message, config.getRagTopK());
        List<MemoryItem> memories = longTermMemory.recall(message, config.getLongTermRecallTopK());
        String systemPrompt = buildSystemPrompt(knowledge, memories);

        AgentResponse response;
        try {
            response = execute(userId, sessionId, message, systemPrompt, knowledge, approved, onChunk);
        } catch (Exception e) {
            log.error("[Agent] chat failed, degrade to fallback reply", e);
            response = AgentResponse.builder()
                    .reply("抱歉，智能助手暂时不可用，请稍后再试或换个说法。")
                    .source("fallback")
                    .knowledge(knowledge)
                    .build();
        }

        // 3. 记录回复
        shortTermMemory.add(MemoryItem.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .content("assistant: " + response.getReply())
                .type(MemoryItem.Type.MESSAGE)
                .build());

        // 4. 沉淀长期记忆
        consolidateMemory(sessionId);

        return response;
    }

    /** 入口守卫 → 确定性流程 或 执行图。 */
    private AgentResponse execute(String userId, String sessionId, String message, String systemPrompt,
                                  List<DocumentChunk> knowledge, boolean approved, Consumer<String> onChunk) {

        Optional<DeterministicFlow> flowOpt = intentRouter.route(message);
        if (flowOpt.isPresent()) {
            DeterministicFlow flow = flowOpt.get();
            log.debug("[Agent] routed to deterministic flow: {}", flow.getName());
            FlowResult result = flow.execute(FlowContext.builder()
                    .userId(userId).sessionId(sessionId).userMessage(message)
                    .shortTermMemory(shortTermMemory).longTermMemory(longTermMemory)
                    .toolRegistry(toolRegistry).ragEngine(ragEngine)
                    .build());
            emit(onChunk, result.getOutput());
            return AgentResponse.builder()
                    .reply(result.getOutput())
                    .source("flow")
                    .knowledge(knowledge)
                    .build();
        }

        // 执行图：计划为空时它会转为直接对话（ReAct 所在的位置）
        // 循环护栏一次请求一份，同时约束图里的环与框架驱动的工具循环
        LoopGuard guard = new LoopGuard(config.getLoopBudget());
        AgentGraph.GraphResult graphResult = agentGraph.run(
                userId, message, systemPrompt, recentConversation(sessionId), approved, guard, onChunk);
        log.info("[Agent] loops {}", guard.summary());

        // 图的「中断出口」：高危操作未确认，图在此结束，等用户确认后作为新请求重入
        if (graphResult.getPendingApproval() != null && !graphResult.getPendingApproval().isEmpty()) {
            String reply = "这个操作涉及「" + String.join("、", graphResult.getPendingApproval())
                    + "」，属于不可撤销的高危操作。确认无误的话，请回复「确认执行」。";
            emit(onChunk, reply);
            return AgentResponse.builder()
                    .reply(reply)
                    .source("approval")
                    .knowledge(knowledge)
                    .pendingActions(graphResult.getPendingApproval())
                    .build();
        }

        return AgentResponse.builder()
                .reply(graphResult.getAnswer())
                .source(graphResult.getSteps().isEmpty() ? "react" : "plan")
                .knowledge(knowledge)
                .toolExecutions(graphResult.getToolExecutions())
                .build();
    }

    private List<ChatMessage> recentConversation(String sessionId) {
        return shortTermMemory.recent(sessionId, config.getMemoryWindow()).stream()
                .map(m -> ChatMessage.builder()
                        .role(m.getContent().startsWith("user:") ? ChatMessage.Role.USER : ChatMessage.Role.ASSISTANT)
                        .content(m.getContent().replaceAll("^(user:|assistant:)", "").trim())
                        .build())
                .toList();
    }

    private void emit(Consumer<String> onChunk, String text) {
        if (onChunk != null && text != null && !text.isEmpty()) {
            onChunk.accept(text);
        }
    }

    private String buildSystemPrompt(List<DocumentChunk> knowledge, List<MemoryItem> memories) {
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
     * 把本轮值得长期记住的事实/偏好沉淀到长期记忆。
     * 失败不影响主链路——记忆是增强项，不是必需项。
     */
    private void consolidateMemory(String sessionId) {
        if (consolidator == null || !config.isMemoryConsolidationEnabled()) {
            return;
        }
        try {
            List<MemoryItem> facts = consolidator.extract(
                    sessionId, shortTermMemory.recent(sessionId, config.getMemoryWindow()));
            facts.forEach(longTermMemory::add);
            if (!facts.isEmpty()) {
                log.debug("[Agent] consolidated {} memory item(s)", facts.size());
            }
        } catch (Exception e) {
            log.warn("[Agent] memory consolidation failed: {}", e.getMessage());
        }
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public Memory getShortTermMemory() {
        return shortTermMemory;
    }

    public Memory getLongTermMemory() {
        return longTermMemory;
    }

    public RAGEngine getRagEngine() {
        return ragEngine;
    }

    @Data
    @Builder
    public static class AgentResponse {
        private String reply;
        /** flow / plan / react / approval / fallback */
        private String source;
        private List<DocumentChunk> knowledge;
        /** 本轮实际发生的工具调用轨迹 */
        private List<ToolExecution> toolExecutions;
        /** 等待用户确认的高危操作 */
        private List<String> pendingActions;
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
        /** 单次请求的循环预算 */
        @Builder.Default private LoopBudget loopBudget = LoopBudget.defaults();
        @Builder.Default private String defaultSystemPrompt = "你是一个智能电商助手，帮助用户选购商品、查询订单、解答售后问题。";
    }
}
