package yumefusaka.envoymart.agent.rag;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 百炼 Embedding 适配器（评测专用）。
 * <p>
 * 走 OpenAI 兼容端点，用于本地对照实验；生产侧由 ai-service 的
 * {@code SpringAiEmbeddingService} 承担，这里是测试内的轻量实现，
 * 避免为了跑一次评测引入完整 Spring 上下文。
 */
@Slf4j
class DashScopeEmbeddingService implements EmbeddingService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
    /** 单次请求的文本上限，避免超长请求被拒 */
    private static final int BATCH_SIZE = 10;

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final Map<String, float[]> cache = new HashMap<>();

    DashScopeEmbeddingService(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        // 只请求未缓存的文本，避免重复调用（评测里文档与查询会反复出现）
        List<String> pending = texts.stream()
                .filter(text -> !cache.containsKey(text))
                .distinct()
                .toList();

        for (int start = 0; start < pending.size(); start += BATCH_SIZE) {
            List<String> batch = pending.subList(start, Math.min(start + BATCH_SIZE, pending.size()));
            List<float[]> vectors = callApi(batch);
            for (int i = 0; i < batch.size(); i++) {
                cache.put(batch.get(i), vectors.get(i));
            }
        }
        return texts.stream().map(cache::get).toList();
    }

    @Override
    public int dimension() {
        return 1024;
    }

    private List<float[]> callApi(List<String> batch) {
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "model", model,
                    "input", batch,
                    "dimensions", 1024));

            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("embedding http " + response.statusCode() + ": "
                        + abbreviate(response.body()));
            }

            Map<String, Object> parsed = MAPPER.readValue(response.body(), new TypeReference<>() {
            });
            List<Map<String, Object>> data = (List<Map<String, Object>>) parsed.get("data");
            return data.stream()
                    .sorted(Comparator.comparingInt(item -> ((Number) item.get("index")).intValue()))
                    .map(item -> toFloats((List<Number>) item.get("embedding")))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("调用 embedding 失败", e);
        }
    }

    private float[] toFloats(List<Number> values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i).floatValue();
        }
        return vector;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
