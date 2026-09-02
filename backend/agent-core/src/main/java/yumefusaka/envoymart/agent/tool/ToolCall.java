package yumefusaka.envoymart.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 工具调用请求 —— LLM 决定调用工具时的入参包装。
 */
@Data
@Builder
@AllArgsConstructor
public class ToolCall {
    private String id;
    private String toolName;
    private Map<String, Object> arguments;

    /** 高危工具是否已获得用户确认 */
    @Builder.Default
    private boolean confirmed = false;

    public ToolCall(String id, String toolName, Map<String, Object> arguments) {
        this(id, toolName, arguments, false);
    }
}
