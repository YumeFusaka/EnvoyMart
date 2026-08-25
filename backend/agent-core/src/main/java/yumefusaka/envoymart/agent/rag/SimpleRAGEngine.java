package yumefusaka.envoymart.agent.rag;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 简易 RAG 引擎 —— 文档摄取 → 切片 → Embedding → 索引 → 检索。
 */
@Slf4j
public class SimpleRAGEngine implements RAGEngine {

    private final VectorStore vectorStore;
    private final Retriever retriever;

    /** 切片大小（字符数） */
    private final int chunkSize;
    /** 切片重叠（字符数） */
    private final int chunkOverlap;

    public SimpleRAGEngine(VectorStore vectorStore,
                           Retriever retriever,
                           int chunkSize,
                           int chunkOverlap) {
        this.vectorStore = vectorStore;
        this.retriever = retriever;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
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
        List<DocumentChunk> chunks = new ArrayList<>();
        String text = doc.getContent();
        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(DocumentChunk.builder()
                    .chunkId(doc.getId() + "_" + index)
                    .docId(doc.getId())
                    .content(text.substring(start, end))
                    .chunkIndex(index++)
                    .build());
            start += chunkSize - chunkOverlap;
        }
        return chunks;
    }
}
