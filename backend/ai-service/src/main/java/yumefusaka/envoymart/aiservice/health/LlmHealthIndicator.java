package yumefusaka.envoymart.aiservice.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.agent.llm.LLMProvider;

/**
 * 模型可用性进入健康检查。
 * <p>
 * 「无 Key 也能启动」是刻意设计，但启动成功不等于能力可用：此前模型未配置时
 * {@code /actuator/health} 依然是 UP，运维与编排层都看不出这个实例其实答不了问题。
 * 这里把「跑在 Mock 上」显式暴露成一个降级状态——不是 DOWN（服务本身是好的，
 * 摘掉它反而丢掉了商品、RAG 等仍可用的能力），而是明确的 DEGRADED。
 */
@Component("llm")
public class LlmHealthIndicator implements HealthIndicator {

    private final LLMProvider llmProvider;

    public LlmHealthIndicator(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    @Override
    public Health health() {
        if (llmProvider.supportsReasoning()) {
            return Health.up()
                    .withDetail("provider", llmProvider.getClass().getSimpleName())
                    .build();
        }
        return Health.status("DEGRADED")
                .withDetail("reason", "未配置模型 Key，当前为占位实现，智能助手无法真正作答")
                .withDetail("provider", llmProvider.getClass().getSimpleName())
                .build();
    }
}
