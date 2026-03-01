package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 向量存储 —— 支持语义相似度检索。
 * <p>
 * 实现方案：内存中的余弦相似度暴力扫描（演示用），
 * 生产环境建议 Elasticsearch dense_vector 或 pgvector。
 */
public interface VectorStore {

    void index(DocumentChunk chunk);

    void indexBatch(List<DocumentChunk> chunks);

    /** 返回按相似度降序排列的 topK 切片。 */
    List<DocumentChunk> search(float[] queryVector, int topK);

    void delete(String docId);
}
