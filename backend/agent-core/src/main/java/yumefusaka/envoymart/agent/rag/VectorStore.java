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
}
