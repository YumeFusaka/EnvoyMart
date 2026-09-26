package yumefusaka.envoymart.agent.rag;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 简易 RAG 引擎 —— 文档摄取 → 切片 → Embedding → 索引 → 检索。
 */
@Slf4j
public class SimpleRAGEngine implements RAGEngine {

    private final VectorStore vectorStore;
    private final Retriever retriever;
    private final TextSplitter splitter;

    /**
     * 使用定长滑动窗口切分 —— 与历史行为完全一致。
     * <p>
     * 保留这个签名是为了不动现有调用方（{@code AiAgentConfig}）；
     * 换用其他策略请走下面的 {@link TextSplitter} 重载。
     */
    public SimpleRAGEngine(VectorStore vectorStore,
                           Retriever retriever,
                           int chunkSize,
                           int chunkOverlap) {
        this(vectorStore, retriever, new FixedSizeSplitter(chunkSize, chunkOverlap));
    }

    /** 指定切分策略。 */
    public SimpleRAGEngine(VectorStore vectorStore,
                           Retriever retriever,
                           TextSplitter splitter) {
        this.vectorStore = vectorStore;
        this.retriever = retriever;
        this.splitter = splitter;
    }

    @Override
    public void ingest(Document document) {
        if (document == null || document.getContent() == null) {
            log.warn("[RAG] skip null document");
            return;
        }
        List<DocumentChunk> chunks = chunk(document);
        // 向量化交给 VectorStore，避免上层与存储层用不同模型编码
        vectorStore.indexBatch(chunks);
        log.info("[RAG] ingested doc={} chunks={}", document.getId(), chunks.size());
    }

    @Override
    public void ingestBatch(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            log.warn("[RAG] ingestBatch called with empty list");
            return;
        }
        documents.forEach(this::ingest);
    }

    @Override
    public List<DocumentChunk> retrieve(String query, int topK) {
        return retriever.retrieve(query, topK);
    }

    private List<DocumentChunk> chunk(Document doc) {
        return splitter.split(doc);
    }
}
