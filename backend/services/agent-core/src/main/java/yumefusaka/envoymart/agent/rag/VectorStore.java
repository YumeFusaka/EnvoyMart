package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 向量存储 —— 支持语义相似度检索。
 */
public interface VectorStore {

    void index(DocumentChunk chunk);

    void indexBatch(List<DocumentChunk> chunks);

    /** 返回按相似度降序排列的 topK 切片。 */
    List<DocumentChunk> search(float[] queryVector, int topK);

    void delete(String docId);
}
