package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 向量存储 —— 支持语义相似度检索。
 * <p>
 * 检索入参是原始查询文本：向量化由实现自己负责，
 * 这样内存实现与远端实现（如 Milvus）才能共用同一套上层逻辑，
 * 也避免上层用 A 模型编码、存储用 B 模型编码导致的向量空间错配。
 */
public interface VectorStore {

    default void index(DocumentChunk chunk) {
        indexBatch(List.of(chunk));
    }

    void indexBatch(List<DocumentChunk> chunks);

    /** 传入原始查询文本，返回按相似度降序排列的 topK 切片。 */
    List<DocumentChunk> search(String query, int topK);

    void deleteByDocId(String docId);

    /**
     * 清空整个集合。
     * <p>
     * 重建索引用的是它，不是逐篇 {@link #deleteByDocId}：后者只清得掉「这次的语料里有的」
     * 那些文档，清不掉语料改名、换目录、或早期种子数据留下的孤儿条目——
     * 它们没有溯源字段，被检索到时模型引用不了、用户点开无处可去，还挤占 topK 的名额。
     * 整份清空把「库里恰好等于当前语料」变成重建的<b>性质</b>，
     * 而不是「只要没人动过语料就成立」的假设。
     */
    void removeAll();
}
