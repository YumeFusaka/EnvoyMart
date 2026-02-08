package yumefusaka.envoymart.agent.tool;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 工具调用请求 —— LLM 决定调用工具时的入参包装。
 */
@Data
@Builder
public class ToolCall {
    private String id;
    private String toolName;
    private Map<String, Object> arguments;
}
