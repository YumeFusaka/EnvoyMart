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

    @Test
    void 缺少用户身份的条目不入库() {
        EpisodicMemory memory = new EpisodicMemory(new RecordingVectorStore());

        memory.add(MemoryItem.builder().id("x").content("没有归属的内容").build());

        assertThat(memory.findById("x")).isEmpty();
    }
}
