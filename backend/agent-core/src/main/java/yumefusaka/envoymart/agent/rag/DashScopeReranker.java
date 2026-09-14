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
 * 百炼 gte-rerank 重排适配器（cross-encoder）。
 * <p>
 * 召回用双塔向量追求"不漏"，重排用 cross-encoder 让 query 与候选逐对打分，
 * 排序更准。任何异常都降级为「不重排」，不让检索整体失败。
 * <p>
 * <b>降级是静默的，所以必须计数。</b>降级后的结果与"配置里根本没用重排"完全一致，
 * 从指标上看不出任何区别——实测中同一份代码、同一套数据，语义档在 0.6 与 0.7 之间跳动，
 * 差异就来自这里。不把降级次数暴露出来，任何"重排效果如何"的结论都无从判断可信度。
 */
@Slf4j
public class DashScopeReranker implements Reranker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final String apiKey;
    private final String model;
    private final String endpoint;
    private final Duration timeout;

    private final java.util.concurrent.atomic.AtomicInteger successCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger degradedCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile String lastDegradeReason;
    private final HttpClient httpClient;

    public DashScopeReranker(String apiKey, String model) {
        this(apiKey, model, DEFAULT_ENDPOINT, Duration.ofSeconds(5));
    }

    public DashScopeReranker(String apiKey, String model, String endpoint, Duration timeout) {
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
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

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("[Rerank] http {} body={}", response.statusCode(), abbreviate(response.body()));
                return degrade(query, candidates, topK, "http " + response.statusCode());
            }

            List<Map<String, Object>> results = MAPPER.readValue(response.body(),
                    new TypeReference<Map<String, Object>>() {
                    })
                    .get("output") instanceof Map<?, ?> output && output.get("results") instanceof List<?> list
                    ? list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                    : List.of();

            if (results.isEmpty()) {
                return degrade(query, candidates, topK, "空结果");
            }

            List<DocumentChunk> reranked = new ArrayList<>(results.size());
            for (Map<String, Object> item : results) {
                int index = ((Number) item.get("index")).intValue();
                if (index >= 0 && index < candidates.size()) {
                    reranked.add(candidates.get(index));
                }
            }
            log.info("[Rerank] model={} candidates={} kept={}", model, candidates.size(), reranked.size());
            successCount.incrementAndGet();
            return reranked.stream().limit(topK).toList();

        } catch (Exception e) {
            log.warn("[Rerank] failed, degrade to original order: {}", e.getMessage());
            return degrade(query, candidates, topK, e.getMessage());
        }
    }

    /** 真正生效过的重排次数 */
    public int successCount() {
        return successCount.get();
    }

    /** 静默降级为"不重排"的次数——结果与没配重排完全一致 */
    public int degradedCount() {
        return degradedCount.get();
    }

    public String lastDegradeReason() {
        return lastDegradeReason;
    }

    private List<DocumentChunk> degrade(String query, List<DocumentChunk> candidates, int topK, String reason) {
        degradedCount.incrementAndGet();
        lastDegradeReason = reason;
        return Reranker.NOOP.rerank(query, candidates, topK);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
