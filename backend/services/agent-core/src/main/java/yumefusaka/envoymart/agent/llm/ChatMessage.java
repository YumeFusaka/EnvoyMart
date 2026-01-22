package yumefusaka.envoymart.agent.llm;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * LLM 消息体，统一内部对话格式。
 */
@Data
@Builder
public class ChatMessage {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    private Role role;
    private String content;

    /** 工具调用请求（assistant 发出） */
    private ToolCallRequest toolCall;

    /** 工具调用结果（tool 角色返回） */
    private String toolResult;

    @Data
    @Builder
    public static class ToolCallRequest {
        private String id;
        private String name;
        private Map<String, Object> arguments;
    }
}
