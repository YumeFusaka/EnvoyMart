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
}
