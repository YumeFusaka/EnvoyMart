package yumefusaka.envoymart.agent.rag;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.llm.TokenLedger;

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

            Map<String, Object> payload = MAPPER.readValue(response.body(),
                    new TypeReference<Map<String, Object>>() {
                    });
            // 重排是这一步里最贵的调用之一（query + 全部候选一起进 cross-encoder），
            // 而它此前完全不在任何成本口径里：账本只覆盖对话模型，检索这条路是另一条链路。
            // 用量只在响应里报一次，就地入账
            recordUsage(payload);

            List<Map<String, Object>> results = payload
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
                    // 把重排分写回切片并标记来源。cross-encoder 的分数是这次查询下
                    // 「这一对 query-doc 有多相关」的直接估计，是拒答门最该用的那把尺子；
                    // 之前这里只搬对象、分数丢掉，等于把最贵的信号扔了。
                    reranked.add(candidates.get(index).toBuilder()
                            .score(relevanceOf(item))
                            .reranked(Boolean.TRUE)
                            .build());
                }
            }
            log.info("[Rerank] model={} candidates={} kept={} topScore={}",
                    model, candidates.size(), reranked.size(),
                    reranked.isEmpty() ? "-" : reranked.get(0).getScore());
            successCount.incrementAndGet();
            return reranked.stream().limit(topK).toList();

        } catch (Exception e) {
            log.warn("[Rerank] failed, degrade to original order: {}", e.getMessage());
            return degrade(query, candidates, topK, e.getMessage());
        }
    }

    /** 真正生效过的重排次数 */
    /**
     * 把响应里的用量记进本轮账本。
     * <p>
     * 重排的输入是「一句话 + 全部候选」，一次调用几千 token 是常态；它不在对话模型那条
     * 调用链上，所以账本在别处记多少都不会带上它。字段名按百炼的口径取
     * {@code usage.total_tokens}——重排没有输出 token 的概念，全部计入输入侧。
     * 取不到就什么都不记：宁可少一笔，也不编一笔。
     */
    private void recordUsage(Map<String, Object> payload) {
        if (!(payload.get("usage") instanceof Map<?, ?> usage)) {
            return;
        }
        Object total = usage.get("total_tokens");
        if (total instanceof Number number && number.intValue() > 0) {
            TokenLedger.record(model, number.intValue(), 0);
        }
    }

    public int successCount() {        return successCount.get();
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

    /**
     * 取 gte-rerank 的 relevance_score。
     * <p>
     * 字段名按 DashScope 文档是 {@code relevance_score}（早期版本也出现过 {@code score}），
     * 两个都认。缺失时返回 {@code null} 而不是 0 —— 0 是一个「极度不相关」的断言，
     * 会被拒答门当成事实采信；null 的意思是「没有这个信号」，两者的后续处理不同。
     */
    private Double relevanceOf(Map<String, Object> item) {
        Object raw = item.containsKey("relevance_score") ? item.get("relevance_score") : item.get("score");
        return raw instanceof Number number ? number.doubleValue() : null;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
