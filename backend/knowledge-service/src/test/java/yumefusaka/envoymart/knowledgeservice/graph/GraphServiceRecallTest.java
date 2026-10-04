package yumefusaka.envoymart.knowledgeservice.graph;

import org.junit.jupiter.api.Test;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.session.Configuration;
import yumefusaka.envoymart.contract.GraphEdge;
import yumefusaka.envoymart.contract.GraphNode;
import yumefusaka.envoymart.contract.Substance;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeDocumentEntity;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeChunkMapper;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeDocumentMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图谱召回的「与用户有关」判据。
 * <p>
 * 这几条用例来自 2026-10-03 实测的一个真实反例：用户问「K2 和鱼油能一起吃吗」，
 * 实体链接命中的是<b>鱼油</b>（图上没有 K2），返回的全是鱼油自己的边——一条都不涉及 K2。
 * 如果召回方把「有边返回」当成「用户问的那件事有依据」，就会把「K2 未收录」
 * 提级成「K2 有图谱支撑」。判据必须是<b>边的至少一端能从用户链接到的实体到达</b>。
 */
class GraphServiceRecallTest {

    static {
        // MyBatis-Plus 的 lambda 列名要靠实体元数据缓存解析；纯单测没有 Spring 容器，
        // 手动初始化一次即可，避免 withTitles 的 lambdaQuery 在测试里抛「找不到 lambda 缓存」
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new Configuration(), ""), KnowledgeDocumentEntity.class);
    }

    private static GraphNode node(String name) {
        return new GraphNode(name, name, "INGREDIENT");
    }

    private static GraphEdge edge(String head, String tail) {
        return new GraphEdge(node(head), "INTERACTS_WITH", "可能增加出血风险", node(tail),
                "KB-0006", null, "KB-0006_4", 402, 432, "与华法林合用时可能增加出血风险", null, List.of());
    }

    /** 换一个 docId/chunkId 的边：否则去重键相同，无关边会被去重吞掉，测不到过滤 */
    private static GraphEdge edgeIn(String docId, String chunkId, String head, String tail) {
        return new GraphEdge(node(head), "INTERACTS_WITH", "可能增加出血风险", node(tail),
                docId, null, chunkId, 402, 432, "与华法林合用时可能增加出血风险", null, List.of());
    }

    @Test
    void 与用户实体无关的边被过滤掉() {
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        when(documentMapper.selectList(any())).thenReturn(List.of());
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        when(store.isAvailable()).thenReturn(true);
        // 用户问的是「K2 和鱼油」——只链接到「深海鱼油」
        when(store.linkEntities(any())).thenReturn(List.of("深海鱼油"));
        when(store.expandSubstances(anyList())).thenReturn(List.of(
                new Substance("深海鱼油", "深海鱼油", "epa", "EPA", "INGREDIENT",
                        List.of("深海鱼油", "EPA"))));
        when(store.compositionOf(anyList())).thenReturn(List.of());
        // 返回一条鱼油自己的边，以及一条与鱼油毫无关系的边（模拟 scope 放大带进来的邻域）
        when(store.risksOf(anyList())).thenReturn(List.of(
                edge("深海鱼油", "华法林"),
                edgeIn("KB-0019", "KB-0019_5", "氨基葡萄糖", "华法林")));

        GraphService service = new GraphService(documentMapper, mock(KnowledgeChunkMapper.class), store);
        List<GraphEdge> result = service.recall("K2 和鱼油能一起吃吗", 5);

        assertThat(result)
                .as("与用户链接实体无关的边必须被过滤；它们会让「K2 未收录」看起来像「K2 有依据」")
                .extracting(e -> e.head().name())
                .containsExactly("深海鱼油");
    }

    @Test
    void 命中边带上可达链() {
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        when(documentMapper.selectList(any())).thenReturn(List.of());
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        when(store.isAvailable()).thenReturn(true);
        when(store.linkEntities(any())).thenReturn(List.of("深海鱼油"));
        when(store.expandSubstances(anyList())).thenReturn(List.of());
        when(store.compositionOf(anyList())).thenReturn(List.of());
        when(store.risksOf(anyList())).thenReturn(List.of(edge("深海鱼油", "华法林")));

        GraphService service = new GraphService(documentMapper, mock(KnowledgeChunkMapper.class), store);
        List<GraphEdge> result = service.recall("鱼油和华法林冲突吗", 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).head().name()).isEqualTo("深海鱼油");
    }
}
