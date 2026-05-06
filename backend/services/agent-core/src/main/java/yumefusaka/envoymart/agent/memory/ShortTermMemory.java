package yumefusaka.envoymart.agent.memory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 短期记忆 —— 每会话滑动窗口，超出上限则丢弃最早条目。
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
        if (deque == null) return List.of();
        synchronized (deque) {
            return deque.stream()
                    .skip(Math.max(0, deque.size() - limit))
                    .collect(Collectors.toList());
        }
    }

    @Override
    public List<MemoryItem> search(String sessionId, String keyword) {
        Deque<MemoryItem> deque = store.get(sessionId);
        if (deque == null) return List.of();
        synchronized (deque) {
            return deque.stream()
                    .filter(item -> item.getContent().contains(keyword))
                    .collect(Collectors.toList());
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
