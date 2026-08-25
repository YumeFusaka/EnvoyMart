package yumefusaka.envoymart.aiservice.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import yumefusaka.envoymart.agent.rag.EmbeddingService;

import java.util.List;

/**
 * Spring AI 向量化适配器 —— 复用 Spring AI 的 EmbeddingModel
 * （百炼 text-embedding-v4 / OpenAI text-embedding-3 等 OpenAI 兼容端点）。
 */
public class SpringAiEmbeddingService implements EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public SpringAiEmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public float[] embed(String text) {
        return embeddingModel.embed(text == null ? "" : text);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return embeddingModel.embed(texts);
    }

    @Override
    public int dimension() {
        return embeddingModel.dimensions();
    }
}
