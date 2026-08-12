package yumefusaka.envoymart.agent.rag;

import java.util.List;
import java.util.Random;

/**
 * 简易 Embedding 实现 —— 基于字符哈希的固定向量。
 * 切换至 OllamaEmbeddingService 即可接入真实向量模型。
 */
public class SimpleEmbeddingService implements EmbeddingService {

    private static final int DIM = 128;
    private final Random rng = new Random(42);

    @Override
    public float[] embed(String text) {
        rng.setSeed(text.hashCode());
        float[] vec = new float[DIM];
        for (int i = 0; i < DIM; i++) {
            vec[i] = rng.nextFloat() * 2 - 1; // [-1, 1]
        }
        return normalize(vec);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    @Override
    public int dimension() {
        return DIM;
    }

    private float[] normalize(float[] vec) {
        double norm = 0;
        for (float v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm == 0) return vec;
        for (int i = 0; i < vec.length; i++) vec[i] /= norm;
        return vec;
    }
}
