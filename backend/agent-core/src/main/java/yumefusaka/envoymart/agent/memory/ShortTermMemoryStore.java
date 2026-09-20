package yumefusaka.envoymart.agent.memory;

import java.util.List;

/**
 * 短期记忆的持久化载体 —— 让会话窗口跨重启、跨实例存活。
 * <p>
 * <b>为什么需要它</b>：会话窗口原本只活在进程内存里。单机单实例时看不出问题，
 * 但它有两处硬伤：<b>重启就丢</b>（用户回来发现助手不记得刚才聊了什么），
 * 以及<b>多实例时同一用户落到不同实例上下文直接断</b>——而项目的业务层是无状态的、
 * 可以水平扩，AI 层一扩就断，这个不对称会让"支持分布式"这句话在架构上站不住。
 * <p>
 * <b>与长期记忆的分工不变</b>：这个载体只负责把<b>会话窗口</b>搬出进程，
 * 隔离维度仍是 sessionId。跨会话的事实与偏好归长期记忆（画像走 Redis、情节走向量库）。
 * <p>
 * 实现方（如 Redis）承担全部失败语义：会话窗口是增强项不是必需项，
 * 读写失败应当降级为"这一轮没有历史上下文"，而不是让对话本身失败。
 */
public interface ShortTermMemoryStore {

    /** 读该会话的最近若干条，<b>按时间正序</b>返回；无记录时返回空列表。 */
    List<MemoryItem> load(String sessionId, int limit);

    /** 追加一条，并把窗口裁剪到 limit 条 —— 滑动窗口的裁剪刀在存储侧也要有一把。 */
    void append(String sessionId, MemoryItem item, int limit);

    /** 清空该会话。 */
    void clear(String sessionId);

    /** 未接入持久化时的空实现 —— 退化为纯内存，与从前行为逐位一致。 */
    ShortTermMemoryStore NOOP = new ShortTermMemoryStore() {
        @Override
        public List<MemoryItem> load(String sessionId, int limit) {
            return List.of();
        }

        @Override
        public void append(String sessionId, MemoryItem item, int limit) {
            // 不持久化
        }

        @Override
        public void clear(String sessionId) {
            // 不持久化
        }
    };
}
