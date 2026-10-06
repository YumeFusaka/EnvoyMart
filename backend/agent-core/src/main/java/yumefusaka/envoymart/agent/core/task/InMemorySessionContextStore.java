package yumefusaka.envoymart.agent.core.task;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话现场的进程内实现 —— 没有配置持久化时用。
 * <p>
 * <b>它的价值是「有胜过无」。</b>单实例部署下它其实已经把整件事做完了：
 * 同一个 JVM 里的下一个请求能看到上一轮的现场。它做不到的是多实例与重启
 * （见 {@link SessionContextStore} 的接口注释）。所以生产路径应当挂 Redis 实现，
 * 这个类用于单测与本地降级。
 */
public class InMemorySessionContextStore implements SessionContextStore {

    private final Map<String, SessionContext> store = new ConcurrentHashMap<>();

    @Override
    public Optional<SessionContext> load(String userId, String sessionId) {
        return Optional.ofNullable(store.get(key(userId, sessionId)));
    }

    @Override
    public void save(SessionContext context) {
        if (context == null) {
            return;
        }
        store.put(key(context.userId(), context.sessionId()), context);
    }

    @Override
    public void clear(String userId, String sessionId) {
        store.remove(key(userId, sessionId));
    }

    private static String key(String userId, String sessionId) {
        return userId + ":" + (sessionId == null ? "-" : sessionId);
    }
}
