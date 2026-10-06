package yumefusaka.envoymart.agent.core.task;

import java.util.Optional;

/**
 * 会话现场的存储 —— 「这个会话正在办的是哪件事」。
 * <p>
 * <b>为什么必须跨请求存在。</b>上下文隔离的判据是「本轮和上一件事有没有重叠」，
 * 而「上一件事」是上一个 HTTP 请求里的东西。只放在进程内还谈不上持久：
 * 多实例部署时下一个请求飘到别的实例，看到的现场就是空的，于是把接续判成切换。
 * 所以生产实现走 Redis（见 ai-service 的 {@code RedisSessionContextStore}）。
 * <p>
 * <b>读写的失败语义与 {@link TaskStateStore} 一致：一律吞掉，降级为「没有现场」。</b>
 * 没有现场的表现是「这一轮按新事项处理」——最坏结果是上下文没接上，
 * 而 <b>让一次存储抖动变成一次回答失败是把增强项的代价转嫁给主链路</b>。
 */
public interface SessionContextStore {

    /** 存取键由「用户 + 会话」共同确定，理由见 {@code Agent#taskId} 的同名取舍 */
    Optional<SessionContext> load(String userId, String sessionId);

    void save(SessionContext context);

    /** 会话结束时清掉现场。留着它会让下一个新会话继承一个不存在的事 */
    void clear(String userId, String sessionId);

    /** 不配置存储时的空实现：每轮都按「没有上一件事」处理 */
    SessionContextStore NOOP = new SessionContextStore() {
        @Override
        public Optional<SessionContext> load(String userId, String sessionId) {
            return Optional.empty();
        }

        @Override
        public void save(SessionContext context) {
        }

        @Override
        public void clear(String userId, String sessionId) {
        }
    };
}
