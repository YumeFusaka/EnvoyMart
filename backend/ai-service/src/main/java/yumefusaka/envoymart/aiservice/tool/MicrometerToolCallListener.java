package yumefusaka.envoymart.aiservice.tool;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import yumefusaka.envoymart.agent.tool.ToolCallListener;

import java.time.Duration;
import java.util.Locale;

/**
 * 把工具调用转成 Micrometer 指标。
 * <p>
 * 挂在 {@code ToolRegistry} 上，所以计划节点、ReAct 循环、MCP 三条来路都能覆盖——
 * 早先埋点在 `ToolRegistryToolCallback` 里，而计划路径不经过那个类，主路径的调用一次都没被统计到。
 */
public class MicrometerToolCallListener implements ToolCallListener {

    private final MeterRegistry meterRegistry;

    public MicrometerToolCallListener(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void onToolCall(String tool, Outcome outcome, long latencyMs) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("agent.tool.calls")
                .tag("tool", tool)
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry)
                .increment();
        if (latencyMs > 0) {
            Timer.builder("agent.tool.latency")
                    .tag("tool", tool)
                    .register(meterRegistry)
                    .record(Duration.ofMillis(latencyMs));
        }
    }
}
