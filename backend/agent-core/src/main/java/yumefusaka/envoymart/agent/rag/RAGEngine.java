package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * RAG 引擎 —— 编排文档摄取 → 切片 → Embedding → 索引 → 检索全流程。
 */
public interface RAGEngine {

    void ingest(Document document);

    void ingestBatch(List<Document> documents);

    List<DocumentChunk> retrieve(String query, int topK);

    /**
     * 带请求级事实的检索 —— 见 {@link RetrievalOutcome}。
     * <p>
     * 只在「本轮有没有图谱依据在场」这类<b>下游推不出来</b>的判断上需要它：
     * 图谱切片可能已被重排截断，结果列表里看不见它，但它确实来过。
     * 默认实现退化为纯文本检索的产出，保证不关心这件事的实现零成本。
     */
    default RetrievalOutcome retrieveWithOutcome(String query, int topK) {
        return RetrievalOutcome.textOnly(retrieve(query, topK));
    }
}
