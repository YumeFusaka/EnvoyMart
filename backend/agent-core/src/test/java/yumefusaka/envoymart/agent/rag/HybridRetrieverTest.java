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

    @Test
    void 同一片被文本与图谱同时召回时图谱身份仍被保留() {
        DocumentChunk original = DocumentChunk.builder()
                .chunkId("KB-0006_4").docId("KB-0006")
                .content("深海鱼油与华法林合用可能增加出血风险。")
                .build();
        DocumentChunk fromGraph = original.toBuilder()
                .source("graph")
                .content("图谱推导：深海鱼油 相互作用 华法林，可能增加出血风险\n"
                        + "原文：深海鱼油与华法林合用可能增加出血风险。")
                .build();

        List<DocumentChunk> result = overChunks(List.of(original), (query, topK) -> List.of(fromGraph))
                .retrieve("华法林 鱼油 相互作用", 3);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent())
                .as("正文仍取原文（转述不如原文）")
                .doesNotContain("图谱推导");
        assertThat(result.get(0).getGraphBacked())
                .as("融合只该丢掉转述正文，不该丢掉「图谱路命中过这一片」这个事实——"
                        + "拒答门的图谱豁免靠它触发，丢了它就等于该分支在真实链路里永不生效")
                .isTrue();
    }

    @Test
    void 未被图谱命中的切片不带图谱标记() {
        DocumentChunk only = DocumentChunk.builder()
                .chunkId("KB-0006_4").docId("KB-0006")
                .content("深海鱼油与华法林合用可能增加出血风险。")
                .build();

        List<DocumentChunk> result = overChunks(List.of(only), (query, topK) -> List.of())
                .retrieve("深海鱼油 华法林", 3);

        assertThat(result.get(0).getGraphBacked())
                .as("没有图谱命中就不该有标记，否则豁免会变成无条件放行")
                .isNull();
    }

    /**
     * U76 的真实形态：<b>文本路与图谱路的文本并不相同，但指的是同一片依据。</b>
     * <p>
     * 图谱路算出来的 {@code chunkId} 与知识库原文切片一致（这是 RRF 能累加的前提），
     * {@code putIfAbsent} 先到者胜会留下先入池的文本版。上面的用例测的是「同一对象
     * 换个 source」，而线上真正发生的是「图谱版也是独立对象，被文本版顶掉」——
     * 两者在 {@code putIfAbsent} 眼里是同一种情况，但后者从未被单独断言过。
     * <p>
     * <b>这里的顺序是关键：图谱路排在最后入池</b>，所以它一定是被顶掉的那一个。
     */
    @Test
    void 图谱版被文本版顶掉之后图谱标记仍然留在融合结果上() {
        DocumentChunk textVersion = DocumentChunk.builder()
                .chunkId("KB-0006_4").docId("KB-0006").title("深海鱼油软胶囊产品说明书")
                .content("深海鱼油与华法林合用可能增加出血风险。")
                .build();
        DocumentChunk graphVersion = textVersion.toBuilder()
                .source("graph")
                .content("图谱推导：深海鱼油 相互作用 华法林，可能增加出血风险\n"
                        + "原文：深海鱼油与华法林合用可能增加出血风险。")
                .build();

        List<DocumentChunk> result = overChunks(List.of(textVersion), (query, topK) -> List.of(graphVersion))
                .retrieve("深海鱼油 华法林 相互作用", 3);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getGraphBacked())
                .as("图谱路确实命中了这一片，就该留下标记；否则 EvidenceGate 的图谱豁免"
                        + "在真实链路里永远触发不了——它的单测构造的是 graph 版切片，"
                        + "而线上留的是文本版")
                .isTrue();
    }

    /**
     * U76 第三层：<b>重排只留 topK 条，图谱切片会被整条丢掉。</b>
     * <p>
     * 实测形态（2026-10-03，三个账号全部历史）：融合后 {@code candidates=9 kept=3}，
     * 图谱切片因为正文是「图谱推导：…」的转述、字面与问题不像，重排分天然低，
     * 排在 topK 之外被截掉。于是它进不了 {@code EvidenceGate} 的判据列表——
     * <b>豁免分支不是「没触发」，是它的输入根本没到。</b>
     * <p>
     * 单测能过是因为构造的候选只有两三条，重排全留得下。真实候选是 3×topK，
     * 这条用例把这个规模差补上：图谱依据只要进过融合池，就必须以某种形态
     * 到达下游（进结果，或作为请求级事实随结果一起带出）。
     */
    @Test
    void 图谱依据进了候选池就必须能被下游看到_哪怕重排把它截在topK之外() {
        DocumentChunk textVersion = DocumentChunk.builder()
                .chunkId("KB-0001_0").docId("KB-0001").title("退货政策")
                .content("大部分商品支持七天无理由退货。")
                .build();
        // 图谱路只认得实体名，返回的是另一片（正文是「图谱推导：…」的转述）
        DocumentChunk graphVersion = DocumentChunk.builder()
                .chunkId("KB-0006_4").docId("KB-0006").title("深海鱼油软胶囊产品说明书")
                .source("graph")
                .content("图谱推导：深海鱼油 相互作用 华法林，可能增加出血风险\n"
                        + "原文：深海鱼油与华法林合用可能增加出血风险。")
                .build();
        // 「重排器」按字面把图谱版排到最后，模拟 cross-encoder 的真实形态：
        // 「图谱推导：…」与用户的问题不像，分数天然低
        Reranker literalFirst = (query, candidates, topK) -> candidates.stream()
                .sorted(java.util.Comparator.comparingInt(
                        c -> (c.getContent() != null && c.getContent().startsWith("图谱推导")) ? 1 : 0))
                .limit(topK)
                .toList();

        List<DocumentChunk> result = HybridRetriever.overChunks(
                        new InMemoryVectorStore(new SimpleEmbeddingService()),
                        List.of(textVersion), literalFirst, (query, topK) -> List.of(graphVersion))
                .retrieve("七天无理由怎么退", 1);

        assertThat(result)
                .as("topK=1 时文本版胜出——它本来就该胜出")
                .hasSize(1);
        assertThat(result.get(0).getGraphBacked())
                .as("被截断之后留在结果里的文本版本来就与图谱无关")
                .isNull();

        // 关键：走带请求级事实的那个入口，图谱依据必须能被下游看到
        RetrievalOutcome outcome = HybridRetriever.overChunks(
                        new InMemoryVectorStore(new SimpleEmbeddingService()),
                        List.of(textVersion), literalFirst, (query, topK) -> List.of(graphVersion))
                .retrieveWithOutcome("七天无理由怎么退", 1);

        assertThat(outcome.graphChunkIds())
                .as("图谱路本轮确实命中了 KB-0006_4 —— 这一事实必须以请求级形态到达下游，"
                        + "否则 EvidenceGate 的图谱豁免在真实链路里永远没有输入。"
                        + "重排只该截断「给模型看的正文列表」，不该抹掉「本轮触达过图谱依据」")
                .containsExactly("KB-0006_4");
    }

    /** 图谱路召回为空时，请求级事实必须如实说「没有」，否则豁免会变成无条件放行 */
    @Test
    void 图谱路没召回时请求级事实为空() {
        DocumentChunk only = DocumentChunk.builder()
                .chunkId("KB-0001_0").docId("KB-0001")
                .content("大部分商品支持七天无理由退货。")
                .build();

        RetrievalOutcome outcome = HybridRetriever.overChunks(
                        new InMemoryVectorStore(new SimpleEmbeddingService()),
                        List.of(only), Reranker.NOOP, (query, topK) -> List.of())
                .retrieveWithOutcome("七天无理由怎么退", 3);

        assertThat(outcome.hasGraphEvidence()).isFalse();
        assertThat(outcome.graphCandidateInPool()).isFalse();
    }
}
