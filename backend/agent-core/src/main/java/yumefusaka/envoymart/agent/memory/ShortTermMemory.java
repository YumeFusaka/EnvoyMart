package yumefusaka.envoymart.agent.memory;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 短期记忆 —— 每会话滑动窗口，超出上限则丢弃最早条目。
 * <p>
 * 隔离维度是 sessionId：这是"这次聊了什么"，跟着会话走，不跨会话。
 * 需要跨会话延续的事实与偏好归长期记忆，按 userId 隔离。
 */
public class ShortTermMemory implements Memory {

    private final int maxSize;
    private final Map<String, Deque<MemoryItem>> store = new ConcurrentHashMap<>();

    public ShortTermMemory(int maxSize) {
        this.maxSize = maxSize;
    }

    @Override
    public void add(MemoryItem item) {
        Deque<MemoryItem> deque = store.computeIfAbsent(item.getSessionId(), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(item);
            while (deque.size() > maxSize) {
                deque.removeFirst();
            }
        }
    }

    @Override
    public List<MemoryItem> recent(String sessionId, int limit) {
        Deque<MemoryItem> deque = store.get(sessionId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return deque.stream()
                    .skip(Math.max(0, deque.size() - limit))
                    .collect(Collectors.toList());
        }
    }

    public int size(String sessionId) {
        Deque<MemoryItem> deque = store.get(sessionId);
        if (deque == null) {
            return 0;
        }
        synchronized (deque) {
            return deque.size();
        }
    }

    @Override
    public Optional<MemoryItem> findById(String id) {
        return store.values().stream()
                .flatMap(Collection::stream)
                .filter(item -> item.getId().equals(id))
                .findFirst();
    }

    @Override
    public void clear(String sessionId) {
        store.remove(sessionId);
    }
}
