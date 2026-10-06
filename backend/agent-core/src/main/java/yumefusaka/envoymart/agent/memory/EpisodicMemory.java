package yumefusaka.envoymart.agent.memory;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.VectorStore;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 情节记忆 —— 用户经历过的事件与细节，按需语义召回。
 * <p>
 * 与 {@link UserProfile} 的分工：画像回答"这个人是谁"（结构化、全量注入），
 * 情节回答"他经历过什么"（自由文本、按需召回）。
 * <p>
 * <b>召回一定带 userId</b>。这是与知识检索最本质的区别：知识库是"找相关内容"，
 * 记忆是"找对这个用户成立的事实"——不带用户维度的记忆检索，召回的是别人的人生。
 */
@Slf4j
public class EpisodicMemory implements Memory {

    /** 单用户条目上限，超出按时间淘汰最旧的 */
    private static final int MAX_ITEMS_PER_USER = 200;

    /**
     * 偏好类条目的独立上限。
     * <p>
     * 偏好不参与「先进先出」的淘汰（见 {@link #evict}），但没有上限的不淘汰就是无界增长：
     * 抽取器每几轮跑一次，"用户是什么样的人"这类句子会一直累积，最终把注入给模型的
     * 上下文撑满。给它的额度比事件小一个量级 —— 一个人的长期特征是有限的，
     * 而经历过的事可以很多。
     */
    private static final int MAX_PREFERENCES_PER_USER = 50;


    private final Map<String, Deque<MemoryItem>> store = new ConcurrentHashMap<>();

    /** 内容去重表：同一句事实被反复抽取时不产生副本 */
    private final Map<String, Map<String, MemoryItem>> dedupIndex = new ConcurrentHashMap<>();

    /** 语义召回用的向量库（可选）。为空时退化为仅内存存储。 */
    private final VectorStore vectorStore;

    public EpisodicMemory() {
        this(null);
    }

    public EpisodicMemory(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void add(MemoryItem item) {
        if (item.getUserId() == null || item.getContent() == null) {
            log.debug("[EpisodicMemory] 跳过缺少 userId 或内容的条目");
            return;
        }
        String key = normalize(item.getContent());
        Map<String, MemoryItem> perUser = dedupIndex.computeIfAbsent(item.getUserId(), k -> new ConcurrentHashMap<>());
        if (perUser.putIfAbsent(key, item) != null) {
            // 同一事实再次被抽取：不重复入库，避免 topK 槽位被同一句话的副本占满
            return;
        }

        Deque<MemoryItem> deque = store.computeIfAbsent(item.getUserId(), k -> new ArrayDeque<>());
        List<String> evictedIds = new ArrayList<>();
        synchronized (deque) {
            deque.addLast(item);
            evict(deque, perUser, evictedIds);
        }
        // 淘汰必须连向量一起删。只从队列里移除的话，被挤出的条目仍留在向量库里，
        // recall 照样把它捞回来——而 findById 已经查不到它，还原出来只剩正文和一个默认类型，
        // 用户看到一条自己从没存过的"记忆"，且永远删不掉（配额每满一次它就多活一次）。
        if (vectorStore != null && !evictedIds.isEmpty()) {
            try {
                vectorStore.deleteByIds(evictedIds);
            } catch (Exception e) {
                log.warn("[EpisodicMemory] 淘汰条目时清理向量库失败 userId={}：{}", item.getUserId(), e.getMessage());
            }
        }

        if (vectorStore != null && item.getType() != MemoryItem.Type.MESSAGE) {
            // docId 存 userId：向量库本身不过滤，但至少要保证筛得出来
            vectorStore.indexBatch(List.of(DocumentChunk.builder()
                    .chunkId(item.getId())
                    .docId(item.getUserId())
                    .content(item.getContent())
                    // 类型与时间必须一起落库：重启后内存队列是空的，读回来的路径只剩这一条，
                    // 少写一个字段，读回来就是默认值，而默认值看起来完全正常
                    .type(item.getType() == null ? null : item.getType().name())
                    .timestamp(item.getTimestamp() == null ? null : item.getTimestamp().toEpochMilli())
                    .build()));
        }
    }

    /**
     * 按价值分层淘汰：事件先走，偏好后走。
     * <p>
     * 原来是无差别 FIFO，于是「用户是学生党」和「用户刚问了衬衫尺码」完全等价，
     * 谁先进来谁先走；而淘汰会连向量一起删，被挤出去就是永久丢失——
     * 下次再问预算，没人记得他是学生。
     * <p>
     * 偏好也不是留着不动：它有自己的额度，到顶后同样按最旧的先走（同一个人的偏好会更新，
     * 新说法才作数）。两条路径都在这里收口，配额判断只有一处。
     */
    private void evict(Deque<MemoryItem> deque, Map<String, MemoryItem> perUser, List<String> evictedIds) {
        int preferences = (int) deque.stream().filter(EpisodicMemory::isPreference).count();
        while (deque.size() > MAX_ITEMS_PER_USER || preferences > MAX_PREFERENCES_PER_USER) {
            MemoryItem evicted = pickEviction(deque, preferences);
            if (evicted == null) {
                return;
            }
            if (isPreference(evicted)) {
                preferences--;
            }
            deque.remove(evicted);
            perUser.remove(normalize(evicted.getContent()));
            evictedIds.add(evicted.getId());
        }
    }

    /**
     * 挑一个该走的：先按类型分层，同层内再按重要性排序，最后才看新旧。
     * <p>
     * <b>三层判据的顺序是刻意的。</b>类型在最前，是因为「偏好」这类记忆的丢失
     * 是不可逆的——它是跨会话累积出来的结论，被挤掉就没有第二次机会；
     * 而事件只是记录，少一条最多是这一轮不提起。把重要性放在类型之前，
     * 会让一条普通的高分事件挤掉一条低分偏好，方向就反了。
     * <p>
     * <b>同层内先看重要性、同分才看新旧。</b>原先是纯 FIFO，于是留下哪条完全取决于
     * 入库顺序——「用户是学生党」和「用户问过衬衫尺码」等价，谁先进谁先走。
     * 现在低分先淘汰，同分时最旧的先走，两层都有确定含义。
     */
    private MemoryItem pickEviction(Deque<MemoryItem> deque, int preferences) {
        MemoryItem worst = null;
        for (MemoryItem candidate : deque) {
            if (isPreference(candidate) && preferences <= MAX_PREFERENCES_PER_USER) {
                continue;
            }
            if (worst == null || isWorse(candidate, worst)) {
                worst = candidate;
            }
        }
        return worst;
    }

    /**
     * {@code candidate} 是否比 {@code incumbent} 更该被淘汰。
     * <p>
     * 先比重要性，低了就走；同分时比时间戳，旧的先走。
     * <b>时间戳这一层不能省</b>：只按重要性的话，两条同分记忆谁走取决于遍历顺序，
     * 而遍历顺序是实现细节（{@link ArrayDeque} 的迭代方向），
     * 把它当成淘汰依据等于让一个不该影响结果的东西决定结果。
     * <p>
     * 时间戳为空按最旧处理：记忆条目理应有时间戳，缺失时保守地当作最旧、
     * 优先淘汰它——一条连时间都记不下来的记忆，留着的价值本就最低。
     */
    private static boolean isWorse(MemoryItem candidate, MemoryItem incumbent) {
        int byScore = Integer.compare(candidate.score(), incumbent.score());
        if (byScore != 0) {
            return byScore < 0;
        }
        return timestampOf(candidate).isBefore(timestampOf(incumbent));
    }

    private static java.time.Instant timestampOf(MemoryItem item) {
        return item.getTimestamp() == null ? java.time.Instant.EPOCH : item.getTimestamp();
    }

    private static boolean isPreference(MemoryItem item) {
        return item.getType() == MemoryItem.Type.PREFERENCE;
    }

    /**
     * 按语义召回该用户的情节记忆。
     * <p>
     * <b>过滤下沉到向量库，不在应用层筛。</b>原先的做法是多取 {@code limit * 20} 条
     * 再由这里筛掉别人的条目——那是一个静默漏召回：记忆库一大，某个用户的相关条目
     * 就被挤出过取窗口，他再也回忆不起自己说过的话，而日志里只显示「召回 0 条」，
     * 与「他确实没提过」完全同形。<b>过取倍数只是把漏召回的阈值推后，没有消除它。</b>
     * <p>
     * {@code mergeRecent} 兜底保留，但它现在管的是另一件事：库里<b>真的</b>没有
     * 足够相关的条目（语义检索没命中，而不是被窗口挤掉）。那时退回近期条目，
     * 宁可相关性弱也不给空。
     */
    @Override
    public List<MemoryItem> recall(String userId, String query, int limit) {
        if (vectorStore == null || userId == null || userId.isBlank()) {
            return List.of();
        }
        List<MemoryItem> mine = vectorStore.search(query, limit, userId).stream()
                .map(this::toMemoryItem)
                .collect(Collectors.toList());

        if (mine.size() < limit) {
            log.debug("[EpisodicMemory] userId={} 语义命中 {} 条（需 {}），用近期条目补齐",
                    userId, mine.size(), limit);
            mine = mergeRecent(userId, mine, limit);
        }
        return mine.stream().limit(limit).toList();
    }

    @Override
    public List<MemoryItem> recent(String sessionId, int limit) {
        return List.of();
    }

    /** 按 userId 取近期条目，用于召回兜底与调试。 */
    public List<MemoryItem> recentOf(String userId, int limit) {
        Deque<MemoryItem> deque = store.get(userId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            List<MemoryItem> all = new ArrayList<>(deque);
            return all.subList(Math.max(0, all.size() - limit), all.size());
        }
    }

    @Override
    public Optional<MemoryItem> findById(String id) {
        return store.values().stream()
                .flatMap(Deque::stream)
                .filter(item -> item.getId().equals(id))
                .findFirst();
    }

    @Override
    public void clear(String userId) {
        Deque<MemoryItem> removed = store.remove(userId);
        dedupIndex.remove(userId);
        // 向量库同样要清 —— 只删内存会让已删条目继续被召回（"幽灵记忆"）
        if (vectorStore != null && userId != null) {
            try {
                vectorStore.deleteByDocId(userId);
            } catch (Exception e) {
                log.warn("[EpisodicMemory] 清理向量库失败 userId={}: {}", userId, e.getMessage());
            }
        }
        if (removed != null) {
            log.info("[EpisodicMemory] 已清除 userId={} 的 {} 条记忆", userId, removed.size());
        }
    }

    public int sizeOf(String userId) {
        Deque<MemoryItem> deque = store.get(userId);
        if (deque == null) {
            return 0;
        }
        synchronized (deque) {
            return deque.size();
        }
    }

    /**
     * 由向量库切片还原记忆条目。
     * <p>
     * 必须带全 type 与 timestamp —— 早先的实现把 type 硬编码成 FACT、时间戳留给默认值，
     * 于是存进去的 PREFERENCE 读出来一律变 FACT，历史条目的时间全变成"此刻"，
     * 而且不报错，只是让所有基于类型与新鲜度的策略静默失效。
     */
    private MemoryItem toMemoryItem(DocumentChunk chunk) {
        MemoryItem known = findById(chunk.getChunkId()).orElse(null);
        if (known != null) {
            return known;
        }
        return MemoryItem.builder()
                .id(chunk.getChunkId())
                .userId(chunk.getDocId())
                .content(chunk.getContent())
                .type(parseType(chunk.getType()))
                .timestamp(chunk.getTimestamp() == null
                        ? Instant.now()
                        : Instant.ofEpochMilli(chunk.getTimestamp()))
                .build();
    }

    /**
     * 类型串还原成枚举。
     * <p>
     * 认不出的一律按事件处理，两个方向的代价不对称：误判成事件的偏好只会被正常淘汰，
     * 误判成偏好的噪声则永久占着不参与淘汰的额度，收不回来。认不出的来路有两类——
     * 升级前入库的老条目没有这个键，以及将来枚举改名。
     */
    private static MemoryItem.Type parseType(String raw) {
        if (raw == null) {
            return MemoryItem.Type.FACT;
        }
        try {
            return MemoryItem.Type.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return MemoryItem.Type.FACT;
        }
    }

    private List<MemoryItem> mergeRecent(String userId, List<MemoryItem> current, int limit) {
        Map<String, MemoryItem> merged = new LinkedHashMap<>();
        current.forEach(item -> merged.put(item.getId(), item));
        List<MemoryItem> recent = recentOf(userId, limit);
        for (int i = recent.size() - 1; i >= 0 && merged.size() < limit; i--) {
            merged.putIfAbsent(recent.get(i).getId(), recent.get(i));
        }
        return new ArrayList<>(merged.values());
    }

    private String normalize(String content) {
        return content == null ? "" : content.replaceAll("\\s+", "").trim();
    }
}
