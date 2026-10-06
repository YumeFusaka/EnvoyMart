package yumefusaka.envoymart.agent.memory;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.VectorStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 情节记忆的隔离与去重 —— 回归防线。
 * <p>
 * 跨用户召回泄漏是原实现最严重的缺陷之一：条目入库时带着归属，检索时却完全不带用户维度，
 * 于是任何一个用户提问都可能把别人的记忆拉进自己的 system prompt。
 */
class EpisodicMemoryTest {

    /** 只做归属记录与线性返回的桩向量库，不涉及真实向量计算 */
    private static final class RecordingVectorStore implements VectorStore {
        private final List<DocumentChunk> chunks = new ArrayList<>();
        private final List<String> deletedDocIds = new ArrayList<>();

        @Override
        public void indexBatch(List<DocumentChunk> batch) {
            chunks.addAll(batch);
        }

        @Override
        public List<DocumentChunk> search(String query, int topK) {
            return chunks.stream().limit(topK).toList();
        }

        @Override
        public void deleteByDocId(String docId) {
            deletedDocIds.add(docId);
            chunks.removeIf(c -> docId.equals(c.getDocId()));
        }

        @Override
        public void deleteByIds(List<String> chunkIds) {
            chunks.removeIf(c -> chunkIds.contains(c.getChunkId()));
        }

        @Override
        public void removeAll() {
            chunks.clear();
        }
    }

    private MemoryItem item(String userId, String content) {
        return typed(userId, content, MemoryItem.Type.FACT);
    }

    private MemoryItem typed(String userId, String content, MemoryItem.Type type) {
        return MemoryItem.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .content(content)
                .type(type)
                .build();
    }

    @Test
    void 召回只返回该用户自己的记忆() {
        RecordingVectorStore store = new RecordingVectorStore();
        EpisodicMemory memory = new EpisodicMemory(store);

        memory.add(item("u1001", "用户收货地址是某大学 3 号楼"));
        memory.add(item("u1002", "用户是 VIP 客户"));

        List<MemoryItem> mine = memory.recall("u1001", "地址", 10);

        assertThat(mine).isNotEmpty();
        assertThat(mine).allSatisfy(m -> assertThat(m.getUserId()).isEqualTo("u1001"));
        assertThat(mine).noneSatisfy(m -> assertThat(m.getContent()).contains("VIP"));
    }

    @Test
    void 缺少用户身份时不召回任何东西() {
        RecordingVectorStore store = new RecordingVectorStore();
        EpisodicMemory memory = new EpisodicMemory(store);
        memory.add(item("u1001", "用户是学生党"));

        assertThat(memory.recall(null, "预算", 10)).isEmpty();
        assertThat(memory.recall("", "预算", 10)).isEmpty();
    }

    @Test
    void 同一内容重复写入不产生副本() {
        RecordingVectorStore store = new RecordingVectorStore();
        EpisodicMemory memory = new EpisodicMemory(store);

        memory.add(item("u1001", "用户是学生党"));
        memory.add(item("u1001", "用户是学生党"));
        memory.add(item("u1001", " 用户是学生党 "));

        assertThat(memory.sizeOf("u1001"))
                .as("反复抽取同一句事实会让 topK 槽位被副本占满")
                .isEqualTo(1);
    }

    @Test
    void 清除记忆时向量库一并清理() {
        RecordingVectorStore store = new RecordingVectorStore();
        EpisodicMemory memory = new EpisodicMemory(store);
        memory.add(item("u1001", "用户是学生党"));

        memory.clear("u1001");

        assertThat(memory.sizeOf("u1001")).isZero();
        assertThat(store.deletedDocIds)
                .as("只删内存会让已删条目继续被召回（幽灵记忆）")
                .contains("u1001");
        assertThat(store.chunks).isEmpty();
    }

    /**
     * 配额淘汰必须连向量一起删。
     * <p>
     * 这是「幽灵记忆」的第二条来路：条目被挤出内存队列后，上层已经 {@code findById} 不到它，
     * 但向量库还留着，{@code recall} 照样把它捞回来——还原出来只剩正文和默认类型，
     * 用户看到一条自己从没存过、也永远删不掉的记忆。清除走的是 {@code deleteByDocId}（另一条测试守着），
     * 淘汰走的是这里。
     */
    @Test
    void 配额淘汰的条目不再被召回() {
        RecordingVectorStore store = new RecordingVectorStore();
        EpisodicMemory memory = new EpisodicMemory(store);

        memory.add(item("u1001", "最早的那条"));
        for (int i = 0; i < 200; i++) {
            memory.add(item("u1001", "第 " + i + " 条"));
        }

        assertThat(memory.sizeOf("u1001")).isEqualTo(200);
        assertThat(store.chunks)
                .as("被挤出的条目仍留在向量库里")
                .noneSatisfy(c -> assertThat(c.getContent()).isEqualTo("最早的那条"));
        assertThat(memory.recall("u1001", "最早", 10))
                .noneSatisfy(m -> assertThat(m.getContent()).isEqualTo("最早的那条"));
    }

    @Test
    void 缺少用户身份的条目不入库() {
        EpisodicMemory memory = new EpisodicMemory(new RecordingVectorStore());

        memory.add(MemoryItem.builder().id("x").content("没有归属的内容").build());

        assertThat(memory.findById("x")).isEmpty();
    }

    /**
     * 换个实例召回时，类型与时间戳必须原样带回来。
     * <p>
     * 这一跳跨了「内存对象 → 向量库元数据 → 内存对象」。中间任何一段漏掉序列化，
     * 读回来拿到的是 builder 的默认值，而且<b>不会报错</b>：所有历史条目都变回
     * 「此刻发生的一件普通事」，于是按类型分层的保留策略、按新鲜度的衰减，
     * 全部在一个错误的前提上静默计算。新实例即进程重启后——内存队列是空的，
     * 只剩向量库这一条来路，正是缺陷显形的位置。
     */
    @Test
    void 换个实例召回时类型与时间戳原样带回来() {
        RecordingVectorStore store = new RecordingVectorStore();
        Instant earlier = Instant.parse("2026-01-02T03:04:05Z");
        new EpisodicMemory(store).add(MemoryItem.builder()
                .id("p1").userId("u1001").content("用户是学生党")
                .type(MemoryItem.Type.PREFERENCE).timestamp(earlier).build());

        List<MemoryItem> recalled = new EpisodicMemory(store).recall("u1001", "预算", 10);

        assertThat(recalled).singleElement().satisfies(m -> {
            assertThat(m.getType()).isEqualTo(MemoryItem.Type.PREFERENCE);
            assertThat(m.getTimestamp()).isEqualTo(earlier);
        });
    }

    /**
     * 长期偏好不该被后来的琐事挤掉。
     * <p>
     * 纯 FIFO 下「用户是学生党」与「用户刚问了衬衫尺码」完全等价，谁先进来谁先走；
     * 而淘汰会连向量库一起删，被挤出去就是永久丢失——下次再问预算，没人记得他是学生。
     */
    @Test
    void 偏好不会被后来源源不断的事件挤出() {
        RecordingVectorStore store = new RecordingVectorStore();
        EpisodicMemory memory = new EpisodicMemory(store);
        memory.add(typed("u1001", "用户是学生党", MemoryItem.Type.PREFERENCE));
        for (int i = 0; i < 250; i++) {
            memory.add(typed("u1001", "第 " + i + " 次咨询", MemoryItem.Type.FACT));
        }

        assertThat(memory.sizeOf("u1001")).isEqualTo(200);
        assertThat(memory.recentOf("u1001", 300))
                .as("事件满配额时该淘汰事件，而不是先入库的偏好")
                .anySatisfy(m -> assertThat(m.getContent()).isEqualTo("用户是学生党"));
    }

    /**
     * 偏好不参与 FIFO 不等于无界增长。
     * <p>
     * 抽取器每几轮就跑一次，长期偏好若只进不出，几年后单用户的偏好清单能撑爆
     * 注入给模型的上下文——保留策略的另一头就是配额。
     */
    @Test
    void 偏好数量也有上限_到顶后淘汰最旧的偏好() {
        RecordingVectorStore store = new RecordingVectorStore();
        EpisodicMemory memory = new EpisodicMemory(store);
        for (int i = 0; i < 60; i++) {
            memory.add(typed("u1001", "偏好 " + i, MemoryItem.Type.PREFERENCE));
        }

        List<String> kept = memory.recentOf("u1001", 100).stream()
                .map(MemoryItem::getContent)
                .toList();

        assertThat(kept).hasSize(50);
        assertThat(kept)
                .as("淘汰最旧的偏好：同一个人的偏好会更新，新说法才作数")
                .contains("偏好 59")
                .doesNotContain("偏好 0");
    }

    /**
     * 容量压力下先淘汰低价值的事件 —— 这是「重要性过滤」的落地判据。
     * <p>
     * 原先是纯 FIFO：同样两条事件，「用户是学生党、预算有限」与「用户问过衬衫尺码」
     * 完全等价，谁先进谁先走。<b>留下哪条纯属运气</b>，而前者的丢失会让后面
     * 每一次推荐都失去约束。这条用例把「低分先走」固定下来。
     */
    @Test
    void 容量压力下先淘汰低价值事件() {
        RecordingVectorStore store = new RecordingVectorStore();
        EpisodicMemory memory = new EpisodicMemory(store);
        // 先塞满低价值噪声（时间上也更早），再放进一条含约束的高价值事实
        for (int i = 0; i < 205; i++) {
            memory.add(item("u2001", "好的"));
        }
        memory.add(item("u2001", "用户是学生党，预算有限"));

        List<String> kept = memory.recentOf("u2001", 300).stream()
                .map(MemoryItem::getContent)
                .toList();

        assertThat(kept)
                .as("含约束词的高价值事实不该被一堆「好的」挤掉")
                .contains("用户是学生党，预算有限");
    }

    /**
     * 过取窗口之外的条目也必须召回 —— 这是「静默漏召回」的回归防线。
     * <p>
     * 原实现是「多取 {@code limit * 20} 条再按 userId 筛」。记忆库一大，
     * 某个用户的相关条目就被挤出窗口，他再也回忆不起自己说过的话，
     * 而日志里只显示「召回 0 条」—— <b>与「他确实没提过」完全同形</b>，
     * 没有任何报错。修法是把过滤下沉到向量库（{@code search(query, topK, docId)}），
     * 「取 topK」的含义因此从「所有切片里取前 topK 再筛」变成「属于我的切片里取前 topK」。
     * <p>
     * 桩向量库刻意不覆写带过滤的方法，走的就是 {@link VectorStore} 的默认实现——
     * 它先按 docId 筛完再截断，所以窗口大小不再影响结果。
     */
    @Test
    void 过取窗口之外的条目也能召回() {
        RecordingVectorStore store = new RecordingVectorStore();
        EpisodicMemory memory = new EpisodicMemory(store);

        // 别人先塞满窗口：50 条起步的过取窗口会被这些条目占满
        for (int i = 0; i < 120; i++) {
            memory.add(item("u9999", "别人的第 " + i + " 条记忆"));
        }
        memory.add(item("u1001", "用户的收货地址是某大学 3 号楼"));

        List<MemoryItem> mine = memory.recall("u1001", "地址", 5);

        assertThat(mine)
                .as("窗口被别人的条目占满时，该用户自己的记忆不该被挤掉")
                .anySatisfy(m -> assertThat(m.getContent()).contains("某大学 3 号楼"));
        assertThat(mine).allSatisfy(m -> assertThat(m.getUserId()).isEqualTo("u1001"));
    }

    /** 召回时带的过滤条件就是调用方的 userId，不是别的键 */
    @Test
    void 召回把过滤条件原样传给向量库() {
        List<String> seenDocIds = new ArrayList<>();
        VectorStore store = new VectorStore() {
            @Override
            public void indexBatch(List<DocumentChunk> batch) {
            }

            @Override
            public List<DocumentChunk> search(String query, int topK) {
                return List.of();
            }

            @Override
            public List<DocumentChunk> search(String query, int topK, String docId) {
                seenDocIds.add(docId);
                return List.of();
            }

            @Override
            public void deleteByDocId(String docId) {
            }

            @Override
            public void deleteByIds(List<String> chunkIds) {
            }

            @Override
            public void removeAll() {
            }
        };
        new EpisodicMemory(store).recall("u1001", "预算", 5);

        assertThat(seenDocIds)
                .as("过滤下沉的关键就是这一跳：把 userId 交给向量库，而不是取回来自己筛")
                .containsExactly("u1001");
    }
    /** 打分是确定的：同样的内容必须得到同样的分，否则「为什么这条丢了」永远答不出来 */
    @Test
    void 重要性打分确定且显式标注优先() {
        MemoryItem plain = item("u3001", "用户问过衬衫尺码");
        MemoryItem constrained = item("u3001", "用户对花生过敏，必须避开");
        MemoryItem marked = MemoryItem.builder()
                .id("m1").userId("u3001").type(MemoryItem.Type.MESSAGE)
                .content("一条普通对话")
                .importance(90)
                .build();

        assertThat(constrained.score()).isGreaterThan(plain.score());
        assertThat(constrained.score()).isEqualTo(constrained.score());
        assertThat(marked.score())
                .as("显式标注一旦给出就完全覆盖启发式，让上游有办法纠正算错的分数")
                .isEqualTo(90);
        assertThat(item("u3001", "好的").score())
                .as("过短内容没有信息量，是被淘汰的那一类")
                .isLessThan(plain.score());
    }
}
