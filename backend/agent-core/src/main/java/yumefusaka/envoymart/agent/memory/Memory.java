package yumefusaka.envoymart.agent.memory;

import java.util.List;
import java.util.Optional;

/**
 * 记忆存储接口 —— 不区分短期/长期，由实现决定持久化策略。
 */
public interface Memory {

    void add(MemoryItem item);

    List<MemoryItem> recent(String sessionId, int limit);

    /**
     * 语义召回：按与 query 的相关性返回<b>该用户</b>的记忆条目。
     * <p>
     * userId 是必填而非可选：记忆检索和知识检索的性质不同——知识库是"找相关内容"，
     * 记忆是"找对这个用户成立的事实"。少了这个维度，检索出来的东西属于谁都不知道。
     * <p>
     * 默认不支持（返回空），由接入向量库的实现覆写。
     */
    default List<MemoryItem> recall(String userId, String query, int limit) {
        return List.of();
    }

    Optional<MemoryItem> findById(String id);

    void clear(String userId);
}
