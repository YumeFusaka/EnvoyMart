package yumefusaka.envoymart.agent.memory;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.VectorStore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 长期记忆 —— 持久化用户偏好、事实、摘要等，不随滑动窗口淘汰。
 */
@Slf4j
public class LongTermMemory implements Memory {

    private final Map<String, List<MemoryItem>> store = new ConcurrentHashMap<>();

    /**
     * 语义召回用的向量库（可选）。为空时退化为仅内存存储。
     * 注意：这是独立于知识库的实例/collection，避免记忆条目污染知识检索结果。
     */
    private final VectorStore vectorStore;

    public LongTermMemory() {
        this(null);
    }

    public LongTermMemory(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void add(MemoryItem item) {
        store.computeIfAbsent(item.getSessionId(), k -> new ArrayList<>()).add(item);
        // 只有沉淀出的事实/偏好才需要语义召回，原始对话消息不入向量库
        if (vectorStore != null && item.getType() != MemoryItem.Type.MESSAGE) {
            vectorStore.indexBatch(List.of(DocumentChunk.builder()
                    .chunkId(item.getId())
                    .docId(item.getSessionId())
                    .content(item.getContent())
                    .build()));
        }
        log.debug("[LongTermMemory] saved type={} sessionId={}", item.getType(), item.getSessionId());
    }

    @Override
    public List<MemoryItem> recall(String query, int limit) {
        if (vectorStore == null) {
            return List.of();
        }
        return vectorStore.search(query, limit).stream()
                .map(chunk -> MemoryItem.builder()
                        .id(chunk.getChunkId())
                        .sessionId(chunk.getDocId())
                        .content(chunk.getContent())
                        .type(MemoryItem.Type.FACT)
                        .build())
                .collect(Collectors.toList());
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
