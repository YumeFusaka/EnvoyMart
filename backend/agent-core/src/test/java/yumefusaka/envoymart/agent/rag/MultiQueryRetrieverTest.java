package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多查询检索的路由边界 —— <b>每种变体只准进它该进的那条路</b>。
 * <p>
 * 这组测试关心的不是「扩写有没有用」（那要看分档指标），而是「扩写有没有放错地方」：
 * 两类变体补的是两种不同的盲区，放错路的代价不是白跑一趟，而是<b>污染</b>——
 * 一段编出来的长文本进了 BM25，会把真正的查询词元按 idf 稀释掉；
 * 一句太短的改写进了向量路，占掉一个本该属于原句的候选位。
 * 这两种错误都不会报异常，只会让召回悄悄变差。
 */
class MultiQueryRetrieverTest {

    private static final List<DocumentChunk> CORPUS = List.of(
            chunk("logistics", "现货订单通常在 24 小时内出库，华东地区预计 1 到 2 天送达。"),
            chunk("after_sale", "除定制类和贴身个护商品外，大部分商品支持七天无理由退货。"));

    /** 只覆写 search 的向量库桩 —— 这一层测的是「哪种变体走了哪条路」，不是语义相似度 */
    private static VectorStore store(Function<String, List<DocumentChunk>> search) {
        return new VectorStore() {
            @Override
            public void indexBatch(List<DocumentChunk> chunks) {
            }

            @Override
            public List<DocumentChunk> search(String query, int topK) {
                return search.apply(query);
            }

            @Override
            public void deleteByDocId(String docId) {
            }

            @Override
            public void deleteByIds(List<String> chunkIds) {
            }

            @Override
            public void removeAll() {
            }
        };
    }

    private static DocumentChunk chunk(String id, String indexText) {
        return DocumentChunk.builder().chunkId(id).docId(id)
                .content(indexText).indexText(indexText).build();
    }

    private static List<String> idsOf(List<DocumentChunk> chunks) {
        return chunks.stream().map(DocumentChunk::getChunkId).toList();
    }

    @Test
    void 假想答案只喂语义路_角度改写只喂词法路() {
        DocumentChunk fromVector = chunk("from-vector", "只可能被向量路捞到的一段话");
        // 词法语料里刻意放了「假想」二字：假想答案若被错喂给 BM25，这一片就会被召回
        DocumentChunk hydeLeak = chunk("hyde-leak", "假想");
        DocumentChunk fromKeyword = chunk("from-keyword", "换个说法的问题");

        HybridRetriever base = HybridRetriever.overChunks(
                store(query -> query.contains("一段假想的资料原文") ? List.of(fromVector) : List.of()),
                List.of(hydeLeak, fromKeyword), Reranker.NOOP);
        MultiQueryRetriever retriever = new MultiQueryRetriever(base,
                query -> new QueryExpansions("一段假想的资料原文", List.of("换个说法的问题")));

        List<String> ids = idsOf(retriever.retrieve("原始问题", 10));

        assertThat(ids)
                .as("假想答案存在的理由就是被向量化——它必须进得了语义路")
                .contains("from-vector");
        assertThat(ids)
                .as("角度改写补的是表述差异，词法路才是它的用武之地")
                .contains("from-keyword");
        assertThat(ids)
                .as("假想答案若也进了 BM25，等于拿一段编出来的长文本稀释真正的查询词元")
                .doesNotContain("hyde-leak");
    }

    @Test
    void 没有扩写时与裸检索器逐位相同() {
        HybridRetriever base = HybridRetriever.overChunks(
                new InMemoryVectorStore(new SimpleEmbeddingService()), CORPUS, Reranker.NOOP);
        String query = "华东地区多久能送到？";

        assertThat(idsOf(new MultiQueryRetriever(base, QueryExpander.NOOP).retrieve(query, 3)))
                .as("开关关掉、模型不可用、没有 Key——三条降级路径都必须落回改造前的行为")
                .isEqualTo(idsOf(base.retrieve(query, 3)));
    }

    @Test
    void 扩写抛异常时退回原句而不是让整个检索失败() {
        HybridRetriever base = HybridRetriever.overChunks(
                new InMemoryVectorStore(new SimpleEmbeddingService()), CORPUS, Reranker.NOOP);
        QueryExpander boom = query -> {
            throw new IllegalStateException("模型超时");
        };

        assertThat(idsOf(new MultiQueryRetriever(base, boom).retrieve("华东地区多久能送到？", 3)))
                .as("扩写是可选增强，它的失败代价不该是用户看不到回答")
                .isNotEmpty()
                .isEqualTo(idsOf(base.retrieve("华东地区多久能送到？", 3)));
    }

    @Test
    void 扩写返回null按没有扩写处理() {
        HybridRetriever base = HybridRetriever.overChunks(
                new InMemoryVectorStore(new SimpleEmbeddingService()), CORPUS, Reranker.NOOP);

        assertThat(idsOf(new MultiQueryRetriever(base, query -> null).retrieve("华东地区多久能送到？", 3)))
                .as("契约写了绝不返回 null，但契约靠不住时不能让 NPE 冒到回答侧")
                .isEqualTo(idsOf(base.retrieve("华东地区多久能送到？", 3)));
    }

    @Test
    void 角度的null列表按空处理() {
        QueryExpansions expansions = new QueryExpansions("一段假想", null);

        assertThat(expansions.angles()).isEmpty();
        assertThat(expansions.isEmpty()).isFalse();
        assertThat(QueryExpansions.none().isEmpty()).isTrue();
    }
}
