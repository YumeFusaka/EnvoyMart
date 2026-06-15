package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * RAG 引擎 —— 编排文档摄取 → 切片 → Embedding → 索引 → 检索全流程。
 */
public interface RAGEngine {

    void ingest(Document document);

    void ingestBatch(List<Document> documents);

    List<DocumentChunk> retrieve(String query, int topK);
}
