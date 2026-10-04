package yumefusaka.envoymart.aiservice.llm;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import yumefusaka.envoymart.agent.rag.QueryExpander;
import yumefusaka.envoymart.agent.rag.QueryExpansions;

/**
 * 查询扩写（HyDE + 多角度改写）的 trace 装饰器（U13）。
 * <p>
 * 扩写既是知识库链路上最容易"静默失效"的一环（无 Key 时短路、模型判空时产出为空、
 * 调用失败时退回原句——三种结果从检索侧看几乎一样），也是最贵的一次额外调用。
 * 把产出规模挂到 span 上，是让"这次到底扩没扩、扩了多少"在单次排查里可查。
 */
public final class TracingQueryExpander implements QueryExpander {

    private final QueryExpander delegate;
    private final ObservationRegistry observationRegistry;

    public TracingQueryExpander(QueryExpander delegate, ObservationRegistry observationRegistry) {
        this.delegate = delegate;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public QueryExpansions expand(String query) {
        Observation observation = ModelTracing.start(observationRegistry, "model.expand");
        try {
            QueryExpansions result = delegate.expand(query);
            // 三个数就把"扩写是不是真的在工作"说清了：假想答案有了没有、改写了几条
            ModelTracing.highCardinality(observation, "hypotheticalChars",
                    String.valueOf(result.hypothetical() == null ? 0 : result.hypothetical().length()));
            ModelTracing.highCardinality(observation, "angles", String.valueOf(result.angles().size()));
            return result;
        } finally {
            observation.stop();
        }
    }
}