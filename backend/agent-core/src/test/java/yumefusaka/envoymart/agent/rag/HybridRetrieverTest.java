package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRetrieverTest {

    private final List<Document> docs = List.of(
            Document.builder().id("after_sale_1").title("七天无理由与售后规则")
                    .content("除定制类和贴身个护商品外，大部分商品支持七天无理由退货；质量问题支持换新与运费补贴。")
                    .tags(List.of("退货", "售后", "退款")).scope("after_sale").build(),
            Document.builder().id("logistics_1").title("物流说明")
                    .content("现货订单通常在 24 小时内出库，华东地区预计 1 到 2 天送达。")
                    .tags(List.of("物流", "快递", "配送")).scope("logistics").build(),
            Document.builder().id("promo_1").title("平台满减规则")
                    .content("本周数码会场满 199 减 20，满 299 减 40；学生认证用户可叠加 95 折校园券。")
                    .tags(List.of("活动", "满减", "优惠")).scope("promotion").build()
    );

    private HybridRetriever retriever() {
        // 向量库故意留空，只验证 BM25 关键词路对中文查询是否命中
        return new HybridRetriever(
                new InMemoryVectorStore(new SimpleEmbeddingService()), docs);
    }

    @Test
    void 中文售后问题命中售后文档() {
        List<DocumentChunk> result = retriever().retrieve("我想退货，七天无理由怎么操作？", 3);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getDocId()).isEqualTo("after_sale_1");
    }

    @Test
    void 中文物流问题命中物流文档() {
        List<DocumentChunk> result = retriever().retrieve("华东地区多久能送到？", 3);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getDocId()).isEqualTo("logistics_1");
    }

    @Test
    void 无关问题不返回结果() {
        assertThat(retriever().retrieve("zzzz", 3)).isEmpty();
    }

    // ==================== 第三路：图谱依据 ====================

    /** 图上那条文本检索够不着的依据：文档里只有「深海鱼油」，没有「SPU5」 */
    private static final DocumentChunk GRAPH_CHUNK = DocumentChunk.builder()
            .chunkId("c12").docId("KB-0005").title("深海鱼油说明书").source("graph")
            .content("图谱推导：深海鱼油 相互作用 华法林，可能增加出血风险\n"
                    + "原文：深海鱼油与华法林合用可能增加出血风险")
            .build();

    private static HybridRetriever overChunks(List<DocumentChunk> chunks, Retriever graph) {
        return HybridRetriever.overChunks(new InMemoryVectorStore(new SimpleEmbeddingService()),
                chunks, Reranker.NOOP, graph);
    }

    @Test
    void 词面与语义都够不着的问题由图谱路捞回() {
        List<DocumentChunk> result = overChunks(List.of(), (query, topK) -> List.of(GRAPH_CHUNK))
                .retrieve("SPU5 和华法林冲突吗", 5);

        assertThat(result)
                .as("SPU5 在任何一篇文档里都不出现，两路文本检索对它无能为力——"
                        + "这条路存在的全部理由就是这一条能进来")
                .extracting(DocumentChunk::getChunkId)
                .containsExactly("c12");
    }

    @Test
    void 图谱路抛异常时退化成文本两路而不是整个检索失败() {
        Retriever boom = (query, topK) -> {
            throw new IllegalStateException("Neo4j 连不上");
        };
        List<DocumentChunk> chunks = List.of(DocumentChunk.builder()
                .chunkId("c1").docId("logistics_1")
                .content("现货订单通常在 24 小时内出库，华东地区预计 1 到 2 天送达。")
                .build());

        assertThat(overChunks(chunks, boom).retrieve("华东地区多久能送到？", 3))
                .as("图谱是可选增强，它的失败不该让用户看不到回答")
                .isNotEmpty();
    }

    @Test
    void 同一片依据被两路同时召回时只出一条() {
        DocumentChunk original = DocumentChunk.builder()
                .chunkId("c1").docId("logistics_1")
                .content("现货订单通常在 24 小时内出库，华东地区预计 1 到 2 天送达。")
                .build();
        // 图谱对同一片依据给出的是转述版（正文前缀不同），chunkId 相同
        DocumentChunk fromGraph = original.toBuilder()
                .source("graph").content("图谱推导：物流 → 华东地区\n原文：华东地区预计 1 到 2 天送达。")
                .build();

        List<DocumentChunk> result = overChunks(List.of(original), (query, topK) -> List.of(fromGraph))
                .retrieve("华东地区多久能送到？", 3);

        assertThat(result)
                .as("按 chunkId 归一是让两路能累加的前提；各起一套 id 的话，"
                        + "同一片依据会在提示词里出现两次，看起来像两份独立出处")
                .hasSize(1);
        assertThat(result.get(0).getContent())
                .as("文本路先到就用原文，图谱那版是转述，作为证据不如原文")
                .doesNotContain("图谱推导");
    }
}
