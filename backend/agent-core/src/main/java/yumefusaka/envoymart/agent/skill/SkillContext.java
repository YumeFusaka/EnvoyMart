package yumefusaka.envoymart.agent.skill;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.agent.memory.Memory;
import yumefusaka.envoymart.agent.rag.RAGEngine;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.Map;

/**
 * Skill 执行上下文 —— 携带当前用户、会话、工具、记忆、RAG 等信息。
 */
@Data
@Builder
public class SkillContext {
    private String userId;
    private String sessionId;
    private String userMessage;
    private Map<String, Object> parameters;

    private Memory shortTermMemory;
    private Memory longTermMemory;
    private ToolRegistry toolRegistry;
    private RAGEngine ragEngine;
}
