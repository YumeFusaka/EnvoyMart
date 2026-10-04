package yumefusaka.envoymart.aiservice.llm;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * 模型调用的 trace 埋点（U13）。
 * <p>
 * <b>为什么是 Observation 而不是自定义 span</b>：Observation 是 Micrometer 的抽象，
 * 它向下能同时对接 metrics 与 tracing——本项目已经引了
 * {@code micrometer-tracing-bridge-otel}，一个 Observation 在开了导出的实例上会变成
 * 一段真实 span，在没开的实例上退化成一次廉价的无操作。**留了通道、接上了一个 Observation，
 * 导出开关（{@code management.tracing.export.enabled}）仍然默认关**——
 * 口径是"探针就位、链路可接"，不是"已接入 Jaeger/Langfuse"。
 * <p>
 * <b>为什么把 attributes 定成这几个</b>：模型名回答"钱花在哪个模型上"，
 * token 回答"这一次花了多少"，工具调用数回答"这一轮是不是绕在工具上"。
 * 这三样恰好是指标按模型聚合时<b>丢掉的粒度</b>——指标能说"DeepSeek 上周花了多少"，
 * 说不清"这次会话为什么花了 5 分钱"，而复盘个案要的正是后者。
 * <p>
 * <b>不注入 registry 时</b>走 {@link ObservationRegistry#NOOP}：单测与无监控环境不需要
 * 额外的装配分支，调用点也不必判空。
 */
public final class ModelTracing {

    private ModelTracing() {
    }

    /**
     * 开启一段模型调用观测。调用方在 try/finally 里包住真实调用。
     *
     * @param registry ObservationRegistry；可为 null，此时返回 NOOP 观测
     * @param name     观测名，形如 {@code model.chat} / {@code model.embed} / {@code model.rerank}
     */
    public static Observation start(ObservationRegistry registry, String name) {
        ObservationRegistry target = registry == null ? ObservationRegistry.NOOP : registry;
        return Observation.start(name, target);
    }

    public static void lowCardinality(Observation observation, String key, String value) {
        if (observation != null && value != null && !value.isBlank()) {
            observation.lowCardinalityKeyValue(key, value);
        }
    }

    public static void highCardinality(Observation observation, String key, String value) {
        if (observation != null && value != null && !value.isBlank()) {
            observation.highCardinalityKeyValue(key, value);
        }
    }
}