package yumefusaka.envoymart.aiservice.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.core.task.SessionContext;
import yumefusaka.envoymart.agent.core.task.SessionContextStore;

import java.time.Duration;
import java.util.Optional;

/**
 * 会话现场的 Redis 持久化。
 * <p>
 * <b>为什么必须跨请求存在（而且必须在 Redis 里）。</b>上下文隔离的判据是
 * 「本轮和上一件事有没有重叠」，而「上一件事」住在上一个 HTTP 请求里。
 * 只放进程内的话，多实例部署时下一个请求飘到别的实例就看不到现场，
 * 于是把接续判成了切换——用户说「那第二个呢」，系统却当成了新话题。
 * <p>
 * <b>TTL 取 7 天。</b>会话现场描述的是「用户此刻还在办的事」，它活得比一轮对话长、
 * 但比长期记忆短得多：一个一周没动过的会话，用户回来时大概率是要重新开始，
 * 而不是接着上次那个没问完的推荐。留着一份过期的「正在办的事」会让新话题被误判成接续。
 * <p>
 * <b>失败语义：读写都吞掉，降级为「没有现场」。</b>理由与 {@code TaskStateStore} 一致——
 * 上下文接不上最坏是「用户得再说一句」，而为此让一次回答失败是把增强项的代价转嫁给主链路。
 */
@Slf4j
@Component
public class RedisSessionContextStore implements SessionContextStore {

    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisSessionContextStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<SessionContext> load(String userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return Optional.empty();
        }
        try {
            String raw = redis.opsForValue().get(key(userId, sessionId));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw, SessionContext.class));
        } catch (Exception e) {
            log.warn("[Session] 会话现场读取失败 userId={} sessionId={}: {}", userId, sessionId, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void save(SessionContext context) {
        if (context == null || context.userId() == null || context.sessionId() == null) {
            return;
        }
        try {
            redis.opsForValue().set(key(context.userId(), context.sessionId()),
                    objectMapper.writeValueAsString(context), TTL);
        } catch (Exception e) {
            log.warn("[Session] 会话现场保存失败 userId={} sessionId={}: {}",
                    context.userId(), context.sessionId(), e.toString());
        }
    }

    @Override
    public void clear(String userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }
        try {
            redis.delete(key(userId, sessionId));
        } catch (Exception e) {
            log.warn("[Session] 会话现场清除失败 userId={} sessionId={}: {}", userId, sessionId, e.toString());
        }
    }

    private static String key(String userId, String sessionId) {
        return "agent:session-context:" + userId + ":" + sessionId;
    }
}
