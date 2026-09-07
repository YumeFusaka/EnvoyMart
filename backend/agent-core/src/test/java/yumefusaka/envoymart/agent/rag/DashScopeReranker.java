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
import java.util.List;
import java.util.Map;

/**
 * 百炼 gte-rerank 适配器（评测专用）。
 * <p>
 * 生产侧由 ai-service 的 {@code DashScopeReranker} 承担；这里是测试内的轻量实现，
 * 失败时降级为不重排，保证对照实验能跑完而不是中途抛错。
 */
@Slf4j
class DashScopeReranker implements Reranker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;

    DashScopeReranker(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public List<DocumentChunk> rerank(String query, List<DocumentChunk> candidates, int topK) {
        if (candidates.size() <= 1) {
            return candidates.stream().limit(topK).toList();
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", Map.of(
                            "query", query,
                            "documents", candidates.stream().map(DocumentChunk::getContent).toList()),
                    "parameters", Map.of("top_n", topK, "return_documents", false));

            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("[Rerank] http {}，降级为不重排", response.statusCode());
                return Reranker.NOOP.rerank(query, candidates, topK);
            }

            Map<String, Object> parsed = MAPPER.readValue(response.body(), new TypeReference<>() {
            });
            List<Map<String, Object>> results = parsed.get("output") instanceof Map<?, ?> output
                    && output.get("results") instanceof List<?> list
                    ? list.stream().map(item -> (Map<String, Object>) item).toList()
                    : List.of();

            List<DocumentChunk> reranked = new ArrayList<>(results.size());
            for (Map<String, Object> item : results) {
                int index = ((Number) item.get("index")).intValue();
                if (index >= 0 && index < candidates.size()) {
                    reranked.add(candidates.get(index));
                }
            }
            return reranked.isEmpty()
                    ? Reranker.NOOP.rerank(query, candidates, topK)
                    : reranked.stream().limit(topK).toList();
        } catch (Exception e) {
            log.warn("[Rerank] 调用失败，降级为不重排：{}", e.getMessage());
            return Reranker.NOOP.rerank(query, candidates, topK);
        }
    }
}
