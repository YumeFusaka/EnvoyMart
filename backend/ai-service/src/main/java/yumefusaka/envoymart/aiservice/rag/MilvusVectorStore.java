package yumefusaka.envoymart.aiservice.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import lombok.extern.slf4j.Slf4j;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.EmbeddingService;
import yumefusaka.envoymart.agent.rag.VectorStore;

import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Milvus 向量库适配器 —— 把 agent-core 的 VectorStore 契约落到 LangChain4j 的 EmbeddingStore。
 * <p>
 * <b>向量化在这里完成，而不是交给 store。</b>LangChain4j 的 EmbeddingStore 只接收已编码的向量
 * （add 的入参是 Embedding，Builder 上也没有 embeddingModel），这与 agent-core 的契约注释
 * 正好一致——「向量化由实现自己负责，避免上层用 A 模型编码、存储用 B 模型编码」。
 */
@Slf4j
public class MilvusVectorStore implements VectorStore {

    private static final String META_DOC_ID = "docId";
    private static final String META_CHUNK_INDEX = "chunkIndex";
    private static final String META_CHUNK_ID = "chunkId";
    private static final String META_TITLE = "title";
    private static final String META_SOURCE = "source";
    private static final String META_SCOPE = "scope";
    private static final String META_VERSION = "version";
    private static final String META_POSITION = "position";
    private static final String META_CHAR_OFFSET = "charOffset";

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
                .map(chunk -> TextSegment.from(chunk.getContent(), metadataOf(chunk)))
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

    @Override
    public void deleteByIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        // chunkId 是索引时写进元数据的，老集合里可能没有这个键——
        // 那时删不掉，但也不该抛异常让整轮对话失败
        try {
            delegate.removeAll(metadataKey(META_CHUNK_ID).isIn(chunkIds));
        } catch (RuntimeException e) {
            log.warn("[Milvus] 按 chunkId 删除失败，已跳过 {} 条：{}", chunkIds.size(), e.getMessage());
        }
    }

    @Override
    public void removeAll() {
        delegate.removeAll();
    }

    /**
     * 拼元数据 —— <b>溯源信息必须随切片一起入库</b>。
     * <p>
     * 这里曾经只存 {@code docId} 与 {@code chunkIndex}，于是标题、来源、版本、位置
     * 在检索回来的那一刻全部为 null：切分阶段辛苦抄下来的东西，
     * 只因为没有过这一道序列化就白抄了。线上表现是引用只能显示一个 {@code docId}，
     * 用户拿不到任何能自己核对的依据。
     * <p>
     * 只放标量：Milvus 的元数据是 JSON 字段，{@link Metadata} 支持的取值里没有布尔，
     * 所以 {@code reranked}、{@code score} 这类「本次查询的属性」不入库——
     * 它们本来也不是切片的固有属性。
     */
    private Metadata metadataOf(DocumentChunk chunk) {
        Metadata metadata = new Metadata();
        putIfPresent(metadata, META_CHUNK_ID, chunk.getChunkId());
        putIfPresent(metadata, META_DOC_ID, chunk.getDocId());
        putIfPresent(metadata, META_TITLE, chunk.getTitle());
        putIfPresent(metadata, META_SOURCE, chunk.getSource());
        putIfPresent(metadata, META_SCOPE, chunk.getScope());
        putIfPresent(metadata, META_VERSION, chunk.getVersion());
        putIfPresent(metadata, META_POSITION, chunk.getPosition());
        metadata.put(META_CHUNK_INDEX, (long) chunk.getChunkIndex());
        if (chunk.getCharOffset() != null) {
            metadata.put(META_CHAR_OFFSET, chunk.getCharOffset().longValue());
        }
        return metadata;
    }

    private void putIfPresent(Metadata metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    /**
     * 反序列化回切片，<b>并带上这一轮的相关性分</b>。
     * <p>
     * {@code match.score()} 是 Milvus 的余弦相似度，取值 {@code [0,1]} 且跨查询可比，
     * 是拒答门在没有重排器时唯一的依据。丢掉的代价是下游只剩「检索到了什么」，
     * 没有「有多确信」。
     */
    private DocumentChunk toChunk(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        Metadata metadata = segment == null ? null : segment.metadata();
        return DocumentChunk.builder()
                .chunkId(string(metadata, META_CHUNK_ID, match.embeddingId()))
                .docId(string(metadata, META_DOC_ID, null))
                .content(segment == null ? null : segment.text())
                .chunkIndex(integer(metadata, META_CHUNK_INDEX))
                .title(string(metadata, META_TITLE, null))
                .source(string(metadata, META_SOURCE, null))
                .scope(string(metadata, META_SCOPE, null))
                .version(string(metadata, META_VERSION, null))
                .position(string(metadata, META_POSITION, null))
                .charOffset(nullableInteger(metadata, META_CHAR_OFFSET))
                .score(Double.valueOf(match.score()))
                .reranked(Boolean.FALSE)
                .build();
    }

    /**
     * 读字符串。历史集合里没有这些键，{@code containsKey} 先挡住；
     * 类型不符（例如别人往同一个键写了数字）也不该让一次检索整体失败。
     */
    private String string(Metadata metadata, String key, String fallback) {
        if (metadata == null || !metadata.containsKey(key)) {
            return fallback;
        }
        try {
            return metadata.getString(key);
        } catch (RuntimeException e) {
            log.warn("[Milvus] 元数据 {} 类型不是字符串，已忽略：{}", key, e.getMessage());
            return fallback;
        }
    }

    private int integer(Metadata metadata, String key) {
        Integer value = nullableInteger(metadata, key);
        return value == null ? 0 : value;
    }

    private Integer nullableInteger(Metadata metadata, String key) {
        if (metadata == null || !metadata.containsKey(key)) {
            return null;
        }
        try {
            Long value = metadata.getLong(key);
            return value == null ? null : value.intValue();
        } catch (RuntimeException e) {
            log.warn("[Milvus] 元数据 {} 类型不是数字，已忽略：{}", key, e.getMessage());
            return null;
        }
    }
}
