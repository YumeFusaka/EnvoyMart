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

    /**
     * 「本品所含 X」是把 X 归属到本品的定语，风险主语是 X —— 这条边陈述的是通用结论。
     * <p>
     * 真实反例 KB-0035_4：head=dha、tail=抗凝或抗血小板药物，整句以「本品所含 DHA」开头。
     * 旧判据只要句中出现「本品」就把整条判成产品专属，于是问「孕期 DHA 与抗凝药」时
     * （绑定表里 KB-0035 的商品是 spu12，而用户没提这个商品）被 PRODUCT_SCOPE_UNPROVEN
     * 拒掉 —— 句中的「本品」只出现在定语里，不是风险陈述的主语。
     */
    @Test
    void 本品作定语时风险主语是成分按通用结论放行() {
        var store = store();
        String quote = "4.1 本品所含 DHA 具有轻微抗血小板聚集作用，与华法林、阿司匹林等抗凝或抗血小板药物合用时可能增加出血风险，合用前须咨询医师。";
        var risk = edgeWithLabel("dha", "DHA", "INGREDIENT", "INTERACTS_WITH", "抗凝或抗血小板药物", "KB-0035", quote);
        when(store.linkEntities(any())).thenReturn(List.of("dha", "抗凝药物"));
        when(store.expandSubstances(anyList())).thenReturn(List.of(
                new Substance("dha", "DHA", "dha", "DHA", "INGREDIENT", List.of("DHA"))));
        when(store.risksOf(anyList())).thenReturn(List.of(risk));
        var service = serviceWithBinding(store, risk, "KB-0035", 12L);
        assertThat(service.recall("孕期 DHA 与抗凝药", 10))
                .as("句中「本品」是定语，风险主语是 DHA —— 通用结论不能因它被拒")
                .extracting(GraphEdge::chunkId).contains("KB-0035_4");
    }

    /**
     * 反向断言：本品<b>自己</b>作风险陈述主语时必须仍被拒 —— 否则缺口 B 的收窄就成了过度放宽。
     * <p>
     * 真实句子 KB-0027 4.2：「本品与噻嗪类利尿剂合用时可能增加高钙血症风险」。
     * 句中「本品」是主语（后面接「与」，不是定语引导词），这条边限定在该商品上；
     * 绑定表里 KB-0027 的商品是 spu10，而用户问的是成分「钙」与药物类别，root 里没有 spu10。
     * <p>
     * <b>这条断言必须能区分「productScoped 命中」与「explicitHead 不成立」</b>：
     * 两个 label（{@code 钙}、{@code 噻嗪类利尿剂}）都在句中出现，所以
     * {@code explicitGeneric} 只差 {@code productScoped} 这一项 —— 去掉本品那一支，
     * 这条边就会被放进来，断言随即变红。
     */
    @Test
    void 本品作风险陈述主语时仍按产品专属拒绝() {
        var store = store();
        String quote = "4.2 本品与噻嗪类利尿剂合用时可能增加高钙血症风险，合用期间应咨询医师。";
        var risk = edgeWithLabel("钙", "钙", "NUTRIENT", "INTERACTS_WITH", "噻嗪类利尿剂", "KB-0027", quote);
        when(store.linkEntities(any())).thenReturn(List.of("钙", "噻嗪类利尿剂"));
        when(store.expandSubstances(anyList())).thenReturn(List.of(
                new Substance("钙", "钙", "钙", "钙", "NUTRIENT", List.of("钙"))));
        when(store.risksOf(anyList())).thenReturn(List.of(risk));
        var service = serviceWithBinding(store, risk, "KB-0027", 10L);
        assertThat(service.recall("钙和噻嗪类利尿剂能一起吃吗", 10))
                .as("「本品与…」的主语是本品，这条边限定在该商品上，不能迁移给别的商品")
                .extracting(GraphEdge::chunkId).doesNotContain("KB-0027_4");
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

    /**
     * 端点带独立展示名的边。{@link #edge} 把 name 与 label 设成同一个值，
     * 而风险作用域判据里的 {@code explicitHead} 比的是 <b>label</b>：
     * DHA 节点的键是 {@code dha}（规范化过），label 才是原文写法「DHA」，
     * 两者不同正是生产形态。
     */
    private static GraphEdge edgeWithLabel(String head, String headLabel, String headKind, String relation,
                                           String tail, String doc, String quote) {
        return new GraphEdge(new GraphNode(head, headLabel, headKind), relation, relation,
                new GraphNode(tail, tail, "DRUG_CLASS"), doc, null, doc + "_4", 0, quote.length(), quote, null, List.of());
    }

    /**
     * 单篇文档 + 单个商品绑定的服务实例。文档内容逐字包含 {@code quote}
     * （{@code riskAppliesTo} 有 SOURCE_MISSING 检查），切片带 docId/chunkId/charOffset
     * （{@code withVerifiedSentenceContext} 有 CURRENT_DOC_CHUNK_MISMATCH 与
     * SENTENCE_OUTSIDE_CURRENT_CHUNK 检查，charOffset 留 null 会被判为无法恢复上下文）。
     */
    private static GraphService serviceWithBinding(KnowledgeGraphStore store, GraphEdge edge,
                                                   String docNo, long spuId) {
        var docs = mock(KnowledgeDocumentMapper.class);
        var doc = new KnowledgeDocumentEntity();
        doc.setId(1L);
        doc.setDocNo(docNo);
        doc.setTitle(docNo);
        doc.setContent(edge.quote());
        when(docs.selectList(any())).thenReturn(List.of(doc));
        var chunks = mock(KnowledgeChunkMapper.class);
        var chunk = new KnowledgeChunkEntity();
        chunk.setDocId(1L);
        chunk.setChunkId(edge.chunkId());
        chunk.setContent(edge.quote());
        chunk.setCharOffset(0);
        when(chunks.selectList(any())).thenReturn(List.of(chunk));
        var bindings = mock(KnowledgeDocProductMapper.class);
        var binding = new KnowledgeDocProductEntity();
        binding.setDocNo(docNo);
        binding.setSpuId(spuId);
        binding.setRole(KnowledgeDocProductEntity.Role.SUBJECT.name());
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        return new GraphService(docs, chunks, store, null, bindings);
    }
}
