package yumefusaka.envoymart.agent.llm;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * LLM 返回结果，支持文本和工具调用两种模式。
 */
@Data
@Builder
public class LLMResponse {
    private String content;
    private List<ChatMessage.ToolCallRequest> toolCalls;
    /** 由接入层（如 Spring AI）代执行的工具调用轨迹 */
    private List<ToolExecution> toolExecutions;
    private int promptTokens;
    private int completionTokens;
    private FinishReason finishReason;

    public enum FinishReason {
        STOP,
        TOOL_CALL,
        LENGTH,
        ERROR
    }
}
