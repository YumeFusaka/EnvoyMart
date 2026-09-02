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

    /**
     * 是否为高危操作（退款、取消订单、扣款等）。
     * 高危工具必须由用户显式确认后才执行，避免模型自主触发不可逆操作。
     */
    @Builder.Default
    private boolean requiresConfirmation = false;

    @Data
    @Builder
    public static class ParameterSpec {
        private String type;         // string / integer / number / boolean
        private String description;
        private boolean required;
    }
}
