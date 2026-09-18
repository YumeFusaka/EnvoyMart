package yumefusaka.envoymart.aiservice.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import yumefusaka.envoymart.agent.rag.EmbeddingService;

import java.util.List;

/**
 * LangChain4j 向量化适配器 —— 复用 LangChain4j 的 EmbeddingModel
 * （百炼 text-embedding-v4 / OpenAI text-embedding-3 等 OpenAI 兼容端点）。
 */
public class LangChain4jEmbeddingService implements EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public LangChain4jEmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public float[] embed(String text) {
        return embeddingModel.embed(text == null ? "" : text).content().vector();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<TextSegment> segments = texts.stream()
                .map(text -> TextSegment.from(text == null ? "" : text))
                .toList();
        return embeddingModel.embedAll(segments).content().stream()
                .map(Embedding::vector)
                .toList();
    }

    @Override
    public int dimension() {
        return embeddingModel.dimension();
    }
}
