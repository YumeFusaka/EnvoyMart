package yumefusaka.envoymart.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Ollama Embedding 服务 —— 调用本地 Ollama  API 生成向量。
 * <p>
 * 默认模型：nomic-embed-text，默认地址：http://127.0.0.1:11434
 * 启动前确保本地已运行 Ollama 并拉取了相应模型。
 */
@Slf4j
public class OllamaEmbeddingService implements EmbeddingService {

    private static final String DEFAULT_URL = "http://127.0.0.1:11434";
    private static final String DEFAULT_MODEL = "nomic-embed-text";
    private static final int DIM = 128;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final String model;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public OllamaEmbeddingService() {
        this(DEFAULT_URL, DEFAULT_MODEL);
    }

    public OllamaEmbeddingService(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public float[] embed(String text) {
        try {
            String body = mapper.writeValueAsString(
                    new OllamaRequest(model, text, false));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[Ollama] embedding failed: status={}", resp.statusCode());
                return fallbackEmbedding(text);
            }

            OllamaEmbeddingResponse result = mapper.readValue(resp.body(), OllamaEmbeddingResponse.class);
            if (result.embedding() == null || result.embedding().isEmpty()) {
                return fallbackEmbedding(text);
            }
            float[] vec = new float[result.embedding().size()];
            for (int i = 0; i < vec.length; i++) vec[i] = result.embedding().get(i).floatValue();
            return normalize(vec);
        } catch (Exception e) {
            log.warn("[Ollama] request failed, use fallback: {}", e.getMessage());
            return fallbackEmbedding(text);
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    @Override
    public int dimension() {
        return DIM;
    }

    /** 降级：Ollama 不可用时使用简单哈希向量 */
    private float[] fallbackEmbedding(String text) {
        java.util.Random rng = new java.util.Random(text.hashCode());
        float[] vec = new float[DIM];
        for (int i = 0; i < DIM; i++) vec[i] = rng.nextFloat() * 2 - 1;
        return normalize(vec);
    }

    private float[] normalize(float[] vec) {
        double norm = 0;
        for (float v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm == 0) return vec;
        for (int i = 0; i < vec.length; i++) vec[i] /= norm;
        return vec;
    }

    private record OllamaRequest(String model, String prompt, boolean stream) {}
    private record OllamaEmbeddingResponse(List<Double> embedding) {}
}
