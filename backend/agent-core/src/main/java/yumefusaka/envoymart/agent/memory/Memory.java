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

    Optional<MemoryItem> findById(String id);

    void clear(String sessionId);
}
