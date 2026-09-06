package yumefusaka.envoymart.agent.flow;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.agent.memory.Memory;
import yumefusaka.envoymart.agent.rag.RAGEngine;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.Map;

/**
 * 确定性流程的执行上下文。
 */
@Data
@Builder
public class FlowContext {
    private String userId;
    private String sessionId;
    private String userMessage;
    private Map<String, Object> parameters;

    private Memory shortTermMemory;
    private Memory longTermMemory;
    private ToolRegistry toolRegistry;
    private RAGEngine ragEngine;
}
