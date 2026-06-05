package yumefusaka.envoymart.agent.tool;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 工具元数据 —— 描述工具的签名、参数、用途，供 LLM 识别调用。
 */
@Data
@Builder
public class ToolDefinition {
    private String name;
    private String description;
    private Map<String, ParameterSpec> parameters;

    @Data
    @Builder
    public static class ParameterSpec {
        private String type;         // string / integer / number / boolean
        private String description;
        private boolean required;
    }
}
