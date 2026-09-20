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
 * <p>
 * <b>内存是本进程的读缓存，不是唯一真相</b>：配了 {@link ShortTermMemoryStore} 时，
 * 本进程没有该会话会先向载体加载一次，写入也同步落进去。载体为 {@code NOOP} 时
 * 行为与"只有内存"逐位一致——这也是测试与本地降级路径走的形态。
 */
public class ShortTermMemory implements Memory {

    private final int maxSize;
    private final Map<String, Deque<MemoryItem>> store = new ConcurrentHashMap<>();
    private final ShortTermMemoryStore persistentStore;

    public ShortTermMemory(int maxSize) {
        this(maxSize, ShortTermMemoryStore.NOOP);
    }

    public ShortTermMemory(int maxSize, ShortTermMemoryStore persistentStore) {
        this.maxSize = maxSize;
        this.persistentStore = persistentStore == null ? ShortTermMemoryStore.NOOP : persistentStore;
    }

    @Override
    public void add(MemoryItem item) {
        Deque<MemoryItem> deque = dequeOf(item.getSessionId());
        synchronized (deque) {
            deque.addLast(item);
            while (deque.size() > maxSize) {
                deque.removeFirst();
            }
        }
        persistentStore.append(item.getSessionId(), item, maxSize);
    }

    @Override
    public List<MemoryItem> recent(String sessionId, int limit) {
        Deque<MemoryItem> deque = dequeOf(sessionId);
        synchronized (deque) {
            return deque.stream()
                    .skip(Math.max(0, deque.size() - limit))
                    .collect(Collectors.toList());
        }
    }

    public int size(String sessionId) {
        Deque<MemoryItem> deque = dequeOf(sessionId);
        synchronized (deque) {
            return deque.size();
        }
    }

    /**
     * 取该会话的双端队列，本进程没有时先向持久化载体加载一次。
     * <p>
     * 不用 {@code computeIfAbsent}：那个方法会在持有 Map 段的锁时执行映射函数，
     * 而这里要做一次可能阻塞的远端调用——把 I/O 放进 Map 的临界区，会把一次慢查询
     * 放大成对该段所有会话的阻塞。
     */
    private Deque<MemoryItem> dequeOf(String sessionId) {
        Deque<MemoryItem> deque = store.get(sessionId);
        if (deque != null) {
            return deque;
        }
        synchronized (store) {
            deque = store.get(sessionId);
            if (deque == null) {
                deque = new ArrayDeque<>(persistentStore.load(sessionId, maxSize));
                store.put(sessionId, deque);
            }
            return deque;
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
        persistentStore.clear(sessionId);
    }
}
