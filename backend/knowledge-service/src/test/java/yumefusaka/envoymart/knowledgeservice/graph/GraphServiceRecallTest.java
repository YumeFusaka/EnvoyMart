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
import static org.mockito.ArgumentMatchers.anyString;
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

    /**
     * 商品文档覆盖：同一篇文档的多条边只算一处，且按文档号升序。
     * <p>
     * 回归背景：「这个商品的说明书接上了没有」在商品编辑页上要给出一个数字。
     * 若按边计数，一篇写了 6 条关系的说明书会显示成 6 篇，管理员会以为传重了。
     */
    @Test
    void 商品文档覆盖按文档去重且只认含有关系() {
        KnowledgeDocumentMapper docMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        when(store.neighborhood("spu7", 1)).thenReturn(List.of(
                new GraphEdge(node("spu7"), "CONTAINS", null, node("深海鱼油"),
                        "KB-0006", null, "KB-0006_0", 24, 35, "主要成分为精制深海鱼油", null, List.of()),
                new GraphEdge(node("spu7"), "CONTAINS", null, node("明胶"),
                        "KB-0006", null, "KB-0006_1", 40, 50, "明胶", null, List.of()),
                new GraphEdge(node("spu7"), "CAUTION_FOR", null, node("孕妇"),
                        "KB-0009", null, "KB-0009_2", 10, 30, "孕妇慎用", null, List.of())
        ));
        when(docMapper.selectList(any())).thenReturn(List.of());
        GraphService service = new GraphService(docMapper, chunkMapper, store);

        var refs = service.documentsOfProduct("spu7");

        // KB-0006 有两条 CONTAINS，只应出现一次；CAUTION_FOR 不算组成关系，不进入文档覆盖
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).docNo()).isEqualTo("KB-0006");
        assertThat(refs.get(0).relations()).isEqualTo(2);
    }

    /**
     * 图外实体必须给出「最近实体」提示，且<b>不能替用户认下来</b>。
     * <p>
     * 用户说「鱼油」、图上节点叫「深海鱼油」时，「没有收录」是事实但不够用——
     * 它让用户以为图谱里查不到相关信息。给一个候选写法，答案才有下一步。
     * 关键在于这一段必须是<b>提示</b>：输入仍然落在 {@code found=false}，
     * 风险列表仍然为空。一旦拿候选去重查，猜错一次就会输出一条张冠李戴的风险结论。
     */
    @Test
    void 图外实体给出最近实体提示但不代答() {
        KnowledgeDocumentMapper docMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        when(store.isAvailable()).thenReturn(true);
        when(store.resolveKeys(anyList())).thenReturn(java.util.Map.of());
        when(store.nearestEntity("鱼油软胶嚷"))
                .thenReturn(java.util.Optional.of(
                        new KnowledgeGraphStore.NearestEntity("深海鱼油", "深海鱼油", 0.9)));
        GraphService service = new GraphService(docMapper, chunkMapper, store);

        var report = service.interactions(List.of("鱼油软胶嚷"));

        assertThat(report.available()).isTrue();
        assertThat(report.items()).hasSize(1);
        var item = report.items().get(0);
        assertThat(item.found()).as("只是相近，不是同一个东西——不能报成已收录").isFalse();
        assertThat(item.hasNearestHint()).isTrue();
        assertThat(item.nearest()).isEqualTo("深海鱼油");
        assertThat(item.risks()).as("提示不得变成依据：没有确认就不能带出任何风险").isEmpty();
    }

    /** 没有相近项时不给提示——宁可说不知道，也不能把「钙片」指到不相关的东西上 */
    @Test
    void 没有相近实体时不给出提示() {
        KnowledgeDocumentMapper docMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        when(store.isAvailable()).thenReturn(true);
        when(store.resolveKeys(anyList())).thenReturn(java.util.Map.of());
        when(store.nearestEntity(anyString())).thenReturn(java.util.Optional.empty());
        GraphService service = new GraphService(docMapper, chunkMapper, store);

        var report = service.interactions(List.of("某不存在的成分"));

        assertThat(report.items().get(0).hasNearestHint()).isFalse();
        assertThat(report.items().get(0).nearest()).isNull();
    }
}
