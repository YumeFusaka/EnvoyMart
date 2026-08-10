package yumefusaka.envoymart.agent.memory.mem0;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * mem0 记忆管理客户端 —— 基于 mem0 的长期记忆存储。
 * <p>
 * 通过 HTTP API 与 mem0 服务通信，管理用户偏好、对话事实等结构化记忆。
 * 当 mem0 服务不可用时，自动降级为内存存储。
 */
@Slf4j
public class Mem0Client {

    private static final String DEFAULT_URL = "http://127.0.0.1:8050";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final String baseUrl;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Map<String, List<Mem0Entry>> fallbackStore;

    public Mem0Client() {
        this(DEFAULT_URL);
    }

    public Mem0Client(String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.mapper = new ObjectMapper();
        this.fallbackStore = new HashMap<>();
    }

    /** 添加记忆条目 */
    public void addMemory(String userId, String sessionId, String content, Map<String, Object> metadata) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("user_id", userId);
            body.put("session_id", sessionId);
            body.put("content", content);
            body.put("metadata", metadata);

            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/memories"))
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("[mem0] API unavailable, using fallback storage");
            fallbackStore.computeIfAbsent(userId, k -> new ArrayList<>())
                    .add(new Mem0Entry(UUID.randomUUID().toString(), content, metadata, System.currentTimeMillis()));
        }
    }

    /** 检索用户相关记忆 */
    public List<Mem0Entry> searchMemory(String userId, String query, int limit) {
        try {
            String url = baseUrl + "/v1/memories?user_id=" + userId + "&query=" + query + "&limit=" + limit;
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return mapper.readValue(resp.body(), mapper.getTypeFactory()
                        .constructCollectionType(List.class, Mem0Entry.class));
            }
        } catch (Exception e) {
            log.warn("[mem0] search failed, use fallback");
        }
        List<Mem0Entry> entries = fallbackStore.getOrDefault(userId, List.of());
        return entries.stream()
                .filter(e -> e.content().contains(query))
                .limit(limit)
                .toList();
    }

    public void clearUserMemory(String userId) {
        fallbackStore.remove(userId);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/memories?user_id=" + userId))
                    .timeout(TIMEOUT)
                    .DELETE()
                    .build();
            client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("[mem0] clear failed");
        }
    }

    public record Mem0Entry(String id, String content, Map<String, Object> metadata, long timestamp) {}
}
