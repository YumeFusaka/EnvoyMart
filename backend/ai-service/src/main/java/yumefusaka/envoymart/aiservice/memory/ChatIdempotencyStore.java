package yumefusaka.envoymart.aiservice.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

/**
 * 对话的请求级幂等。
 * <p>
 * <b>它挡的是什么：</b>同一个 {@code X-Request-Id} 在窗口内再次到达 —— 用户双击发送、
 * 前端超时重试、代理重放。这些是<b>同一件事被送了两遍</b>，第二遍不该再跑一次 Agent
 * （会重复调模型、重复执行工具，写操作还可能落两次）。
 * <p>
 * <b>它不挡什么：</b>用户过一会儿想再问一遍同样的话。那是新的一次提问，网关会发新的
 * 请求号，两者在数据上无从区分 —— 所以判据只能是「同一个请求号」，不能是「内容相同」。
 * 窗口也因此必须短：它覆盖的是网络重试，不是人的思考间隔。
 * <p>
 * <b>为什么返回上次的结果而不是「忽略第二次」：</b>重试方要的是一份回答。
 * 返回空会让前端把它当成一次失败。
 * <p>
 * <b>失败立场是 fail-open：</b>Redis 不可用时照常执行。幂等是<b>防重复</b>，
 * 而 fail-closed 会变成「缓存挂了就不能提问」—— 拿可用性换一个概率性的防护，不划算。
 * 代价是 Redis 抖动期间重复提交会真的执行两遍，这一点由业务层的条件更新兜底。
 */
@Slf4j
@Component
public class ChatIdempotencyStore {

    /** 重试窗口。够任何网络重试与用户双击，又短到不会把「再问一次」误吞 */
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ChatIdempotencyStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 占位：首次到达时写入「进行中」，返回 true；窗口内重复到达返回 false。
     * <p>
     * 用 {@code setIfAbsent} 而不是「先查再写」：后者在并发重试下必然失效 ——
     * 两个线程可以同时查到「没有」，然后同时写。判这件事的只能是 Redis 的原子性。
     */
    public boolean tryAcquire(String userId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            // 没有请求号就没有判据。放行而不是拒绝：这条链路是日志关联字段，
            // 不该因为它缺失就让一次正常提问不可用
            return true;
        }
        try {
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent(key(userId, requestId), "PENDING", WINDOW);
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException e) {
            log.warn("[Idem] 幂等占位失败，本次照常执行 requestId={}: {}", requestId, e.toString());
            return true;
        }
    }

    /** 完成时写下结果，供重复请求复用 */
    public void complete(String userId, String requestId, Object response) {
        if (requestId == null || requestId.isBlank() || response == null) {
            return;
        }
        try {
            redis.opsForValue().set(key(userId, requestId),
                    objectMapper.writeValueAsString(response), WINDOW);
        } catch (RuntimeException e) {
            log.warn("[Idem] 幂等结果写入失败 requestId={}: {}", requestId, e.toString());
        } catch (Exception e) {
            log.warn("[Idem] 幂等结果序列化失败 requestId={}: {}", requestId, e.toString());
        }
    }

    /** 读回上一次的结果。没有、或仍是「进行中」占位时为空 */
    public <T> Optional<T> previous(String userId, String requestId, Class<T> type) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        try {
            String raw = redis.opsForValue().get(key(userId, requestId));
            if (raw == null || raw.isBlank() || "PENDING".equals(raw)) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw, type));
        } catch (Exception e) {
            log.warn("[Idem] 幂等结果读取失败 requestId={}: {}", requestId, e.toString());
            return Optional.empty();
        }
    }

    private static String key(String userId, String requestId) {
        return "chat:idem:" + userId + ":" + requestId;
    }
}
