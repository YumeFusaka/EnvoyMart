package yumefusaka.envoymart.aiservice.llm;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.QueryExpander;
import yumefusaka.envoymart.agent.rag.QueryExpansions;
import yumefusaka.envoymart.agent.rag.Reranker;

import java.util.List;

/**
 * 检索侧模型调用的 trace 装饰器（U13）——重排与查询扩写。
 * <p>
 * <b>为什么不把埋点写进 agent-core 的实现类</b>：那是框架层，至今零 Spring、零 Micrometer
 * 依赖，为了两行埋点把一个监控库引进去，会让"换掉监控"变成"改框架"。装饰器放在接入层，
 * 实现类保持纯粹，通道也仍然只接一次。
 * <p>
 * <b>为什么 rerank 与 expand 要分开观测</b>：两者都是真实计费的模型调用，但失败语义不同——
 * 重排失败降级为"维持原序"，扩写失败降级为"按原句检索"。合成一段 span 之后，
 * "这一轮检索为什么慢"里就分不清是哪一次调用慢。分开之后在导出侧可以按名筛选。
 */
public final class TracingReranker implements Reranker {

    private final Reranker delegate;
    private final ObservationRegistry observationRegistry;

    public TracingReranker(Reranker delegate, ObservationRegistry observationRegistry) {
        this.delegate = delegate;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public List<DocumentChunk> rerank(String query, List<DocumentChunk> candidates, int topK) {
        Observation observation = ModelTracing.start(observationRegistry, "model.rerank");
        try {
            ModelTracing.highCardinality(observation, "candidates", String.valueOf(candidates.size()));
            ModelTracing.highCardinality(observation, "topK", String.valueOf(topK));
            List<DocumentChunk> result = delegate.rerank(query, candidates, topK);
            // 重排的"降级"是静默的（结果与没配重排完全一致），把标记挂到 span 上，
            // 单次排查时才看得出这一次到底有没有真的精排
            ModelTracing.lowCardinality(observation, "degraded",
                    delegate instanceof yumefusaka.envoymart.agent.rag.DashScopeReranker reranker
                            && reranker.lastDegradeReason() != null ? "true" : "false");
            return result;
        } finally {
            observation.stop();
        }
    }
}