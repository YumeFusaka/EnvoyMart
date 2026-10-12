package yumefusaka.envoymart.knowledgeservice.graph;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.contract.GraphEdge;
import yumefusaka.envoymart.contract.GraphNode;
import yumefusaka.envoymart.contract.Substance;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeDocumentEntity;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeChunkMapper;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeDocumentMapper;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeDocProductMapper;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeDocProductEntity;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeChunkEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphRiskScopeTest {
    @BeforeAll
    static void 初始化表元信息() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "医学作用域回归"), KnowledgeDocumentEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "医学主体归属回归"), KnowledgeDocProductEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "医学原片回归"), KnowledgeChunkEntity.class);
    }

    @Test
    void 成分共同存在不能迁移另一产品的过敏禁忌() {
        var store = store();
        var foreign = edge("DHA", "INGREDIENT", "CAUTION_FOR", "KB0041", "对鱼类或本品任一成分过敏者禁用。", "鱼类过敏");
        var own = edge("spu41", "PRODUCT", "CAUTION_FOR", "KB0049", "甲壳类过敏者不宜使用本品。", "甲壳类过敏");
        var general = edge("DHA", "INGREDIENT", "INTERACTS_WITH", "KB-GENERAL", "DHA与阿司匹林合用可能增加出血风险。", "阿司匹林");
        when(store.risksOf(anyList())).thenReturn(List.of(foreign, own, general));
        when(store.compositionOf(anyList())).thenReturn(List.of(
                edge("spu41", "PRODUCT", "CONTAINS", "KB0049", "本品含DHA。", "DHA")));
        var service = service(store);
        assertThat(service.interactions(List.of("SPU41", "阿司匹林")).items().getFirst().risks())
                .extracting(GraphEdge::docId).containsExactly("KB0049", "KB-GENERAL");
        assertThat(service.recall("SPU41与阿司匹林", 10)).extracting(GraphEdge::docId)
                .doesNotContain("KB0041").contains("KB0049", "KB-GENERAL");
    }

    @Test
    void 本品风险需要当前主体绑定而非存量组成边() {
        var store = store();
        var own = edge("DHA", "INGREDIENT", "CAUTION_FOR", "KB0049", "出血性疾病患者使用本品前咨询医师。", "出血性疾病");
        when(store.risksOf(anyList())).thenReturn(List.of(own));
        when(store.compositionOf(anyList())).thenReturn(List.of(
                edge("spu41", "PRODUCT", "CONTAINS", "KB0049", "本品含DHA。", "DHA")));
        assertThat(service(store).interactions(List.of("SPU41")).items().getFirst().risks()).hasSize(1);
        assertThat(service(store, false).interactions(List.of("SPU41")).items().getFirst().risks()).isEmpty();
    }

    @Test
    void 已声明的主体关联无需人工重建且另一商品不能迁移() {
        var store = store();
        var risk = edge("DHA", "INGREDIENT", "CAUTION_FOR", "KB0049", "出血性疾病患者使用本品前咨询医师。", "出血性疾病");
        when(store.risksOf(anyList())).thenReturn(List.of(risk));
        var docs = mock(KnowledgeDocumentMapper.class);
        var doc = new KnowledgeDocumentEntity(); doc.setDocNo("KB0049"); doc.setContent(risk.quote());
        when(docs.selectList(any())).thenReturn(List.of(doc));
        var bindings = mock(KnowledgeDocProductMapper.class);
        var binding = new KnowledgeDocProductEntity(); binding.setDocNo("KB0049"); binding.setSpuId(41L);
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        var service = new GraphService(docs, mock(KnowledgeChunkMapper.class), store, null, bindings);
        assertThat(service.interactions(List.of("SPU41")).items().getFirst().risks()).hasSize(1);
        binding.setSpuId(33L);
        assertThat(service.interactions(List.of("SPU41")).items().getFirst().risks()).isEmpty();
    }

    @Test
    void 缺失事实源或引用不在当前正文时跳过存量边() {
        var store = store();
        var risk = edge("DHA", "INGREDIENT", "INTERACTS_WITH", "KB-GENERAL", "DHA与阿司匹林合用可能增加出血风险。", "阿司匹林");
        when(store.risksOf(anyList())).thenReturn(List.of(risk));
        var docs = mock(KnowledgeDocumentMapper.class);
        when(docs.selectList(any())).thenReturn(List.of());
        var service = new GraphService(docs, mock(KnowledgeChunkMapper.class), store);
        assertThat(service.recall("SPU41与阿司匹林", 10)).isEmpty();
        var changed = new KnowledgeDocumentEntity(); changed.setDocNo("KB-GENERAL"); changed.setContent("新版本未说明该关系。");
        when(docs.selectList(any())).thenReturn(List.of(changed));
        assertThat(service.interactions(List.of("SPU41")).items().getFirst().risks()).isEmpty();
        changed.setContent(risk.quote()); changed.setStatus(0);
        assertThat(service.interactions(List.of("SPU41")).items().getFirst().risks()).isEmpty();
    }

    @Test
    void 直接询问成分也不能把本品跨行禁忌迁移为成分通用禁忌() {
        var store = store();
        when(store.resolveKeys(anyList())).thenReturn(Map.of("dha", "DHA"));
        when(store.linkEntities(any())).thenReturn(List.of("DHA"));
        when(store.expandSubstances(anyList())).thenReturn(List.of(
                new Substance("DHA", "DHA", "DHA", "DHA", "INGREDIENT", List.of("DHA"))));
        when(store.risksOf(anyList())).thenReturn(List.of(
                edge("DHA", "INGREDIENT", "CAUTION_FOR", "KB0041", "DHA为成分。\n对鱼类或本品任一成分过敏者禁用。", "魚类过敏")));
        assertThat(service(store).interactions(List.of("DHA")).items().getFirst().risks()).isEmpty();
        assertThat(service(store).recall("DHA禁忌", 10)).isEmpty();
    }

    private static KnowledgeGraphStore store() {
        var store = mock(KnowledgeGraphStore.class);
        when(store.isAvailable()).thenReturn(true);
        when(store.resolveKeys(anyList())).thenReturn(Map.of("spu41", "spu41", "阿司匹林", "阿司匹林"));
        when(store.linkEntities(any())).thenReturn(List.of("spu41", "阿司匹林"));
        when(store.expandSubstances(anyList())).thenReturn(List.of(
                new Substance("spu41", "磷虾油软胶囊", "DHA", "DHA", "INGREDIENT", List.of("磷虾油软胶囊", "DHA"))));
        when(store.compositionOf(anyList())).thenReturn(List.of());
        when(store.combinationsOf(anyList())).thenReturn(List.of());
        return store;
    }

    private static GraphService service(KnowledgeGraphStore store) {
        return service(store, true);
    }

    private static GraphService service(KnowledgeGraphStore store, boolean ownBindings) {
        var docs = mock(KnowledgeDocumentMapper.class);
        var documents = store.risksOf(List.of()).stream().map(edge -> {
            var doc = new KnowledgeDocumentEntity();
            doc.setDocNo(edge.docId());
            doc.setTitle(edge.docId());
            doc.setContent(edge.quote());
            return doc;
        }).toList();
        when(docs.selectList(any())).thenReturn(documents);
        var bindings = mock(KnowledgeDocProductMapper.class);
        var binding = new KnowledgeDocProductEntity(); binding.setDocNo("KB0049"); binding.setSpuId(ownBindings ? 41L : 33L);
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        return new GraphService(docs, mock(KnowledgeChunkMapper.class), store, null, bindings);
    }

    private static GraphEdge edge(String head, String kind, String relation, String doc, String quote, String tail) {
        return new GraphEdge(new GraphNode(head, head, kind), relation, relation,
                new GraphNode(tail, tail, "DRUG"), doc, null, doc + "_0", 0, quote.length(), quote, null, List.of());
    }
}
