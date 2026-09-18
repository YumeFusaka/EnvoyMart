package yumefusaka.envoymart.aiservice.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.EmbeddingService;
import yumefusaka.envoymart.agent.rag.VectorStore;

import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Milvus 向量库适配器 —— 把 agent-core 的 VectorStore 契约落到 LangChain4j 的 EmbeddingStore。
 * <p>
 * <b>向量化在这里完成，而不是交给 store。</b>LangChain4j 的 EmbeddingStore 只接收已编码的向量
 * （add 的入参是 Embedding，Builder 上也没有 embeddingModel），这与 agent-core 的契约注释
 * 正好一致——「向量化由实现自己负责，避免上层用 A 模型编码、存储用 B 模型编码」。
 */
public class MilvusVectorStore implements VectorStore {

    private static final String META_DOC_ID = "docId";
    private static final String META_CHUNK_INDEX = "chunkIndex";

    private final EmbeddingStore<TextSegment> delegate;
    private final EmbeddingService embeddingService;

    public MilvusVectorStore(EmbeddingStore<TextSegment> delegate, EmbeddingService embeddingService) {
        this.delegate = delegate;
        this.embeddingService = embeddingService;
    }

    @Override
    public void indexBatch(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<Embedding> embeddings = chunks.stream()
                .map(chunk -> Embedding.from(embeddingService.embed(chunk.getContent())))
                .toList();
        List<TextSegment> segments = chunks.stream()
                .map(chunk -> TextSegment.from(chunk.getContent(),
                        Metadata.from(Map.<String, Object>of(
                                META_DOC_ID, chunk.getDocId(),
                                META_CHUNK_INDEX, chunk.getChunkIndex()))))
                .toList();
        delegate.addAll(embeddings, segments);
    }

    @Override
    public List<DocumentChunk> search(String query, int topK) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(embeddingService.embed(query)))
                .maxResults(topK)
                .build();
        return delegate.search(request).matches().stream()
                .map(this::toChunk)
                .toList();
    }

    @Override
    public void deleteByDocId(String docId) {
        delegate.removeAll(metadataKey(META_DOC_ID).isEqualTo(docId));
    }

    private DocumentChunk toChunk(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        Metadata metadata = segment == null ? null : segment.metadata();
        return DocumentChunk.builder()
                .chunkId(match.embeddingId())
                .docId(metadata == null ? null : metadata.getString(META_DOC_ID))
                .content(segment == null ? null : segment.text())
                .build();
    }
}
