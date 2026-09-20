package yumefusaka.envoymart.agent.rag;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的对话客户端（<b>评测专用</b>）。
 * <p>
 * 与 {@link DashScopeEmbeddingService}、{@link DashScopeReranker} 同样的定位：
 * 生产侧由 ai-service 的 {@code LangChain4jLLMProvider} 承担，这里是一份轻量实现，
 * 让效果评测不必拉起完整的 Spring 上下文。
 * <p>
 * <b>刻意不做的事</b>：不执行工具、不重试、不管会话。评测要的是"给定提示词，模型答什么"，
 * 工具循环那套在这里是噪声。
 * <p>
 * 超时给了 60 秒：评测算"批量任务"，不是单次交互。这一点踩过坑——
 * 重排器用面向单次交互的 5 秒默认值，连打 240 次就大面积超时，
 * 而 {@code HttpTimeoutException.getMessage()} 返回 null，看上去像"没有原因的失败"。
 */
@Slf4j
class OpenAiChatClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一次调用的结果，含 token —— 成本模型要用 */
    record Reply(String content, int promptTokens, int completionTokens) {
        int totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;

    OpenAiChatClient(String baseUrl, String apiKey, String model) {
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * 单轮问答。
     *
     * @param temperature 评测取低温（0.2）求可复现；裁判取 0 求判定稳定
     */
    Reply chat(String systemPrompt, String userMessage, double temperature) {
        Map<String, Object> payload = Map.of(
                "model", model,
                "temperature", temperature,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)));

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("chat 调用失败 http " + response.statusCode()
                        + "：" + abbreviate(response.body()));
            }

            JsonNode root = MAPPER.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            int prompt = root.path("usage").path("prompt_tokens").asInt(0);
            int completion = root.path("usage").path("completion_tokens").asInt(0);
            return new Reply(content, prompt, completion);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("chat 调用被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("chat 调用异常：" + e.getMessage(), e);
        }
    }

    private static String abbreviate(String raw) {
        if (raw == null) {
            return "";
        }
        // 错误响应里可能整段回显请求，截断避免日志被刷爆
        return raw.length() <= 300 ? raw : raw.substring(0, 300) + "…";
    }
}
