package yumefusaka.envoymart.agent.memory;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 长期记忆 —— 持久化用户偏好、事实、摘要等，不随滑动窗口淘汰。
 */
@Slf4j
public class LongTermMemory implements Memory {

    private final Map<String, List<MemoryItem>> store = new ConcurrentHashMap<>();

    @Override
    public void add(MemoryItem item) {
        store.computeIfAbsent(item.getSessionId(), k -> new ArrayList<>()).add(item);
        log.debug("[LongTermMemory] saved type={} sessionId={}", item.getType(), item.getSessionId());
    }

    @Override
    public List<MemoryItem> recent(String sessionId, int limit) {
        List<MemoryItem> items = store.get(sessionId);
        if (items == null) return List.of();
        int size = items.size();
        return items.subList(Math.max(0, size - limit), size);
    }

    @Override
    public List<MemoryItem> search(String sessionId, String keyword) {
        List<MemoryItem> items = store.get(sessionId);
        if (items == null) return List.of();
        return items.stream()
                .filter(item -> item.getContent().contains(keyword))
                .collect(Collectors.toList());
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

    /** 将短期记忆中的事实/偏好固化到长期记忆。 */
    public void consolidate(String sessionId, List<MemoryItem> facts) {
        facts.forEach(this::add);
        log.info("[LongTermMemory] consolidated {} facts for session {}", facts.size(), sessionId);
    }
}
