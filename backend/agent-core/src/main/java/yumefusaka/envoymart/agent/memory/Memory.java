package yumefusaka.envoymart.agent.memory;

import java.util.List;
import java.util.Optional;

/**
 * 记忆存储接口 —— 不区分短期/长期，由实现决定持久化策略。
 */
public interface Memory {

    void add(MemoryItem item);

    List<MemoryItem> recent(String sessionId, int limit);

    List<MemoryItem> search(String sessionId, String keyword);

    /**
     * 语义召回：按与 query 的相关性返回记忆条目。
     * 默认不支持（返回空），由接入向量库的实现覆写。
     */
    default List<MemoryItem> recall(String query, int limit) {
        return List.of();
    }

    Optional<MemoryItem> findById(String id);

    void clear(String sessionId);
}
