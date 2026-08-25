package yumefusaka.envoymart.aiservice.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.VectorStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Milvus 向量库适配器 —— 把 agent-core 的 VectorStore 契约落到 Spring AI 的 VectorStore。
 * <p>
 * 向量化由 Spring AI 的 EmbeddingModel 完成（经 MilvusVectorStore 内部调用），
 * 上层只传原始文本，避免编码模型错配。
 */
public class MilvusVectorStore implements VectorStore {

    private static final String META_DOC_ID = "docId";
    private static final String META_CHUNK_INDEX = "chunkIndex";

    private final org.springframework.ai.vectorstore.VectorStore delegate;

    public MilvusVectorStore(org.springframework.ai.vectorstore.VectorStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public void indexBatch(List<DocumentChunk> chunks) {
        List<Document> documents = chunks.stream()
                .map(chunk -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put(META_DOC_ID, chunk.getDocId());
                    metadata.put(META_CHUNK_INDEX, chunk.getChunkIndex());
                    return new Document(chunk.getChunkId(), chunk.getContent(), metadata);
                })
                .toList();
        delegate.add(documents);
    }

    @Override
    public List<DocumentChunk> search(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();
        return delegate.similaritySearch(request).stream()
                .map(doc -> DocumentChunk.builder()
                        .chunkId(doc.getId())
                        .docId(String.valueOf(doc.getMetadata().get(META_DOC_ID)))
                        .content(doc.getText())
                        .build())
                .toList();
    }

    @Override
    public void deleteByDocId(String docId) {
        delegate.delete(META_DOC_ID + " == '" + docId + "'");
    }
}
