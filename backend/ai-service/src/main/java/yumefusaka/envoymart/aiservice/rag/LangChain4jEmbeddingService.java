package yumefusaka.envoymart.aiservice.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.llm.TokenLedger;
import yumefusaka.envoymart.agent.rag.EmbeddingService;
import yumefusaka.envoymart.aiservice.llm.ModelTracing;

import java.util.List;

/**
 * LangChain4j 向量化适配器 —— 复用 LangChain4j 的 EmbeddingModel
 * （百炼 text-embedding-v4 / OpenAI text-embedding-3 等 OpenAI 兼容端点）。
 */
@Slf4j
public class LangChain4jEmbeddingService implements EmbeddingService {

    private final EmbeddingModel embeddingModel;
    /** trace 通道（U13）。可为 null，观测退化为无操作。 */
    private final ObservationRegistry observationRegistry;

    public LangChain4jEmbeddingService(EmbeddingModel embeddingModel) {
        this(embeddingModel, null);
    }

    public LangChain4jEmbeddingService(EmbeddingModel embeddingModel, ObservationRegistry observationRegistry) {
        this.embeddingModel = embeddingModel;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public float[] embed(String text) {
        Observation observation = ModelTracing.start(observationRegistry, "model.embed");
        try {
            ModelTracing.lowCardinality(observation, "model", modelName());
            return record(embeddingModel.embed(text == null ? "" : text)).vector();
        } finally {
            observation.stop();
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        Observation observation = ModelTracing.start(observationRegistry, "model.embed");
        try {
            ModelTracing.lowCardinality(observation, "model", modelName());
            ModelTracing.highCardinality(observation, "batchSize", String.valueOf(texts.size()));
            List<TextSegment> segments = texts.stream()
                    .map(text -> TextSegment.from(text == null ? "" : text))
                    .toList();
            return record(embeddingModel.embedAll(segments)).stream()
                    .map(Embedding::vector)
                    .toList();
        } finally {
            observation.stop();
        }
    }

    private String modelName() {
        return embeddingModel.modelName() == null ? "embedding" : embeddingModel.modelName();
    }

    /**
     * 记一笔向量化的用量，再把内容原样交回。
     * <p>
     * 单次提问的向量化只有几十 token，看着可以忽略；但<b>入库那侧不是</b>——
     * 重建索引要跑完整库，一次几十万 token。同一个适配器两边都在用，
     * 分开记就成了「检索便宜、入库免费」，而入库才是真正花钱的那一头。
     * <p>
     * 用量取不到时返回 {@code totalTokens()} 为 null，此时不记账——
     * 有些 OpenAI 兼容端点不返回 usage，按字符数估一个数出来会让账本混进假数据，
     * 而账本是要拿来对账的。
     */
    private <T> T record(Response<T> response) {
        if (response.tokenUsage() != null && response.tokenUsage().totalTokenCount() != null) {
            String model = embeddingModel.modelName() == null ? "embedding" : embeddingModel.modelName();
            int tokens = response.tokenUsage().totalTokenCount();
            TokenLedger.record(model, tokens, 0);
            // 这行与账本相互独立，是给账本留的对照：只有两边加起来的数对不上，
            // 才知道某一天有一笔没记上（见 scripts/verify-usage.mjs）
            log.info("[Embed] model={} tokens={}", model, tokens);
        }
        return response.content();
    }

    @Override
    public int dimension() {
        return embeddingModel.dimension();
    }
}
