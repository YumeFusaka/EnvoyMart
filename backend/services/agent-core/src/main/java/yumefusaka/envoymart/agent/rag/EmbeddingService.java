package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * Embedding 服务 —— 将文本转换为向量表示。
 */
public interface EmbeddingService {

    float[] embed(String text);

    List<float[]> embedBatch(List<String> texts);

    int dimension();
}
