package yumefusaka.envoymart.knowledgeservice.graph;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.contract.ProductGraphCoverage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 商品资料覆盖率的三种状态。
 * <p>
 * 这个读数存在的意义是「把存储质量的欠账看见」，所以三条用例钉的都是**看见之后该做什么**：
 * 有节点有文档 → 已覆盖；有节点无文档 → 去补说明书；无节点 → 去重建图谱。
 * <p>
 * 最容易错的一条是「无节点」。它看起来像「这个商品一件都没覆盖」，
 * 但根因完全不同——重建一次图谱可能就全好了，而去传说明书是白做。
 */
class GraphServiceCoverageTest {

    private static GraphService serviceWith(KnowledgeGraphStore store) {
        return new GraphService(mock(yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeDocumentMapper.class),
                mock(yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeChunkMapper.class), store);
    }

    @Test
    void 有节点且有文档算已覆盖() {
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        when(store.isAvailable()).thenReturn(true);
        when(store.productCoverage(anyList())).thenReturn(Map.of("spu7",
                new KnowledgeGraphStore.ProductCoverageView(true, 2)));

        ProductGraphCoverage coverage = serviceWith(store)
                .coverage(List.of(new GraphService.SpuRef("SPU7", "鱼油软胶囊")));

        assertThat(coverage.totalSpu()).isEqualTo(1);
        assertThat(coverage.coveredSpu()).isEqualTo(1);
        assertThat(coverage.uncoveredSpu()).isEmpty();
        assertThat(coverage.available()).isTrue();
    }

    @Test
    void 有节点但没有文档边时报需要补说明书() {
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        when(store.isAvailable()).thenReturn(true);
        // 节点在图上（图谱建过），但没有任何 CONTAINS 边指向文档 —— 说明书没入库
        when(store.productCoverage(anyList())).thenReturn(Map.of("spu9",
                new KnowledgeGraphStore.ProductCoverageView(true, 0)));

        ProductGraphCoverage coverage = serviceWith(store)
                .coverage(List.of(new GraphService.SpuRef("SPU9", "维生素 K2 软胶囊")));

        assertThat(coverage.coveredSpu()).isZero();
        assertThat(coverage.uncoveredSpu())
                .singleElement()
                .satisfies(u -> {
                    assertThat(u.reason()).isEqualTo(ProductGraphCoverage.Reason.NO_DOCUMENT);
                    assertThat(u.spuKey()).isEqualTo("SPU9");
                    assertThat(u.name()).isEqualTo("维生素 K2 软胶囊");
                });
    }

    @Test
    void 图上没有节点时报需要重建图谱() {
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        when(store.isAvailable()).thenReturn(true);
        when(store.productCoverage(anyList())).thenReturn(Map.of("spu21",
                new KnowledgeGraphStore.ProductCoverageView(false, 0)));

        ProductGraphCoverage coverage = serviceWith(store)
                .coverage(List.of(new GraphService.SpuRef("SPU21", "锌硒宝片")));

        assertThat(coverage.uncoveredSpu())
                .singleElement()
                .satisfies(u -> assertThat(u.reason()).isEqualTo(ProductGraphCoverage.Reason.NO_NODE));
    }

    @Test
    void 图谱不可用时不算成全未覆盖() {
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        when(store.isAvailable()).thenReturn(false);
        when(store.unavailableReason()).thenReturn("连不上 Neo4j");

        ProductGraphCoverage coverage = serviceWith(store)
                .coverage(List.of(new GraphService.SpuRef("SPU7", "鱼油软胶囊")));

        // 关键：available=false，而不是「1 个商品全都没覆盖」。后者会让人去白传一份说明书
        assertThat(coverage.available()).isFalse();
        assertThat(coverage.reason()).isEqualTo("连不上 Neo4j");
        assertThat(coverage.uncoveredSpu()).isEmpty();
    }

    @Test
    void 空商品清单返回空读数而不是异常() {
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        when(store.isAvailable()).thenReturn(true);

        ProductGraphCoverage coverage = serviceWith(store).coverage(List.of());

        assertThat(coverage.totalSpu()).isZero();
        assertThat(coverage.available()).isTrue();
    }
}