package yumefusaka.envoymart.agent.memory;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.VectorStore;

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
        return MemoryItem.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .content(content)
                .type(MemoryItem.Type.FACT)
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
}
