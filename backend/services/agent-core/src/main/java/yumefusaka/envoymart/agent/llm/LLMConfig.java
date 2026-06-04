package yumefusaka.envoymart.agent.llm;

import lombok.Builder;
import lombok.Data;

/**
 * LLM 调用配置：模型、温度、最大 Token 等。
 */
@Data
@Builder
public class LLMConfig {
    private String model;
    @Builder.Default private double temperature = 0.7;
    @Builder.Default private int maxTokens = 2048;
    @Builder.Default private int topK = 40;
    private String baseUrl;
    private String apiKey;
}
