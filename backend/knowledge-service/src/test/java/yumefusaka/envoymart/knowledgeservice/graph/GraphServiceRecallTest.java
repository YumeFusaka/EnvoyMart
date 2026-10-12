package yumefusaka.envoymart.knowledgeservice.graph;

import org.junit.jupiter.api.Test;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.session.Configuration;
import yumefusaka.envoymart.contract.GraphEdge;
import yumefusaka.envoymart.contract.GraphNode;
import yumefusaka.envoymart.contract.Substance;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeDocumentEntity;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeChunkEntity;
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
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new Configuration(), ""), KnowledgeChunkEntity.class);
    }

    private static GraphNode node(String name) {
        return new GraphNode(name, name, "INGREDIENT");
    }

    private static GraphEdge edge(String head, String tail) {
        return new GraphEdge(node(head), "INTERACTS_WITH", "可能增加出血风险", node(tail),
                "KB-0006", null, "KB-0006_4", 402, 432, head + "与" + tail + "合用时可能增加出血风险", null, List.of());
    }

    /** 换一个 docId/chunkId 的边：否则去重键相同，无关边会被去重吞掉，测不到过滤 */
    private static GraphEdge edgeIn(String docId, String chunkId, String head, String tail) {
        return new GraphEdge(node(head), "INTERACTS_WITH", "可能增加出血风险", node(tail),
                docId, null, chunkId, 402, 432, head + "与" + tail + "合用时可能增加出血风险", null, List.of());
    }

    private static KnowledgeDocumentEntity sourceDocument(String docNo, String content) {
        var document = new KnowledgeDocumentEntity();
        document.setDocNo(docNo);
        document.setTitle(docNo);
        document.setContent(content);
        return document;
    }

    @Test
    void KG03双主体完整2点4来源在limit前优于组成桥和单侧邻居() {
        String query = "铁叶酸片中的铁会影响左旋多巴吸收吗？";
        String quote = "2.4 铁剂与左旋多巴、甲状腺素合用会降低后者吸收，应间隔至少 2 小时。";
        var target = new GraphEdge(node("铁"), "INTERACTS_WITH", "降低吸收", node("左旋多巴"),
                "KB-0010", null, "KB-0010_5", 0, quote.length(), quote, null, List.of());
        var bridge = new GraphEdge(new GraphNode("spu30", "铁叶酸片", "PRODUCT"), "CONTAINS", null,
                node("铁"), "KB-0009", null, "KB-0009_0", 0, 6, "本品含铁。", null, List.of());
        var neighbors = java.util.stream.IntStream.range(0, 4).mapToObj(index -> {
            String text = "铁与钙存在吸收竞争。";
            return new GraphEdge(node("铁"), "INTERACTS_WITH", "吸收竞争", node("钙"),
                    "KB-N" + index, null, "KB-N" + index + "_0", 0, text.length(), text, null, List.of());
        }).toList();
        var risks = new java.util.ArrayList<>(neighbors); risks.add(target);
        var docs = mock(KnowledgeDocumentMapper.class);
        var documents = new java.util.ArrayList<KnowledgeDocumentEntity>();
        for (var risk : risks) {
            var document = sourceDocument(risk.docId(), risk.quote());
            document.setId((long) documents.size() + 1); documents.add(document);
        }
        when(docs.selectList(any())).thenReturn(documents);
        var chunks = mock(KnowledgeChunkMapper.class);
        var current = new KnowledgeChunkEntity(); current.setDocId(documents.getLast().getId());
        current.setChunkId(target.chunkId()); current.setContent(quote); current.setCharOffset(0);
        when(chunks.selectList(any())).thenReturn(List.of(current));
        var store = mock(KnowledgeGraphStore.class); when(store.isAvailable()).thenReturn(true);
        when(store.linkEntities(any())).thenReturn(List.of("spu30", "左旋多巴"));
        when(store.expandSubstances(anyList())).thenReturn(List.of(
                new Substance("spu30", "铁叶酸片", "铁", "铁", "INGREDIENT", List.of("铁叶酸片", "铁")),
                substance("左旋多巴", "左旋多巴")));
        when(store.compositionOf(anyList())).thenReturn(List.of(bridge));
        when(store.risksOf(anyList())).thenReturn(risks);
        var service = new GraphService(docs, chunks, store);
        assertThat(service.recall(query, 3)).hasSize(3).first()
                .satisfies(edge -> {
                    assertThat(edge.chunkId()).isEqualTo("KB-0010_5");
                    assertThat(edge.quote()).isEqualTo(quote);
                    assertThat(edge.chain()).containsExactly("铁叶酸片", "铁");
                });
        when(store.linkEntities(any())).thenReturn(List.of("spu30"));
        assertThat(service.recall("铁叶酸片有什么注意事项？", 1)).first()
                .satisfies(edge -> assertThat(edge.relation()).isEqualTo("CONTAINS"));
        when(store.linkEntities(any())).thenReturn(List.of("spu30", "钙"));
        when(store.expandSubstances(anyList())).thenReturn(List.of(
                new Substance("spu30", "铁叶酸片", "铁", "铁", "INGREDIENT", List.of("铁叶酸片", "铁")),
                substance("钙", "钙")));
        assertThat(service.recall("铁叶酸片与钙会相互影响吗？", 1)).first()
                .satisfies(edge -> assertThat(edge.chunkId()).isNotEqualTo("KB-0010_5"));
        when(store.linkEntities(any())).thenReturn(List.of("spu30", "左旋多巴"));
        documents.getLast().setStatus(0);
        assertThat(service.recall(query, 10)).extracting(GraphEdge::chunkId).doesNotContain("KB-0010_5");
    }

    @Test
    void G13从同原片连续2点4句恢复间隔不借2点3钙条件() {
        String quote = "铁剂与左旋多巴、甲状腺素合用会降低后者吸收";
        String body = "2.3 钙与四环素类抗生素应间隔3小时。\n2.4 " + quote + "，应间隔至少2小时。\n2.5 其它说明。";
        var doc = sourceDocument("KB-0010", body); doc.setId(10L);
        var chunk = new KnowledgeChunkEntity(); chunk.setDocId(10L); chunk.setChunkId("KB-0010_5");
        chunk.setCharOffset(0); chunk.setContent(body);
        var docs = mock(KnowledgeDocumentMapper.class); when(docs.selectList(any())).thenReturn(List.of(doc));
        var chunks = mock(KnowledgeChunkMapper.class); when(chunks.selectList(any())).thenReturn(List.of(chunk));
        var store = mock(KnowledgeGraphStore.class); when(store.isAvailable()).thenReturn(true);
        when(store.linkEntities(any())).thenReturn(List.of("铁剂", "左旋多巴"));
        when(store.expandSubstances(anyList())).thenReturn(List.of(substance("铁剂", "铁剂"), substance("左旋多巴", "左旋多巴")));
        when(store.resolveKeys(anyList())).thenReturn(java.util.Map.of("铁剂", "铁剂", "左旋多巴", "左旋多巴"));
        int start = body.indexOf(quote);
        var risk = new GraphEdge(node("铁剂"), "INTERACTS_WITH", "降低吸收", node("左旋多巴"),
                "KB-0010", null, "KB-0010_5", start, start + quote.length(), quote, null, List.of());
        when(store.risksOf(anyList())).thenReturn(List.of(risk));
        var service = new GraphService(docs, chunks, store);
        GraphEdge result = service.recall("铁剂与左旋多巴", 5).getFirst();
        assertThat(result.quote()).contains("2.4", quote, "应间隔至少2小时").doesNotContain("钙", "3小时");
        assertThat(body.substring(result.quoteStart(), result.quoteEnd())).isEqualTo(result.quote());
        assertThat(service.interactions(List.of("铁剂", "左旋多巴")).items().getFirst().risks().getFirst().quote())
                .isEqualTo(result.quote());
        chunk.setDocId(11L);
        assertThat(service.recall("铁剂与左旋多巴", 5).getFirst().quote()).isEqualTo(quote);
        chunk.setDocId(10L); chunk.setChunkId("OTHER");
        assertThat(service.recall("铁剂与左旋多巴", 5).getFirst().quote()).isEqualTo(quote);
        chunk.setChunkId("KB-0010_5");
        when(store.risksOf(anyList())).thenReturn(List.of(new GraphEdge(risk.head(), risk.relation(), risk.effect(), risk.tail(),
                risk.docId(), null, risk.chunkId(), 0, quote.length(), quote, null, List.of())));
        assertThat(service.recall("铁剂与左旋多巴", 5).getFirst().quote()).isEqualTo(quote);
    }

    @Test
    void 与用户实体无关的边被过滤掉() {
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        when(documentMapper.selectList(any())).thenReturn(List.of(
                sourceDocument("KB-0006", "深海鱼油与华法林合用时可能增加出血风险"),
                sourceDocument("KB-0019", "氨基葡萄糖与华法林合用时可能增加出血风险")));
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
        when(documentMapper.selectList(any())).thenReturn(List.of(
                sourceDocument("KB-0006", "深海鱼油与华法林合用时可能增加出血风险")));
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

    // ==================== 原文明示的共同服用条件 ====================

    private static GraphEdge combo(String members, String tail) {
        return new GraphEdge(new GraphNode("combo:" + members, members.replace("|", " + "), "COMBINATION"),
                "COMBINED_WITH", "在肠道吸收阶段会发生竞争并彼此抑制", node(tail),
                "KB-0053", null, "KB-0053_0", 18, 80,
                "钙剂、铁剂、锌三者同时补充时，在肠道吸收阶段会发生竞争并彼此抑制，建议不要同时服用，至少错开两小时。",
                null, List.of());
    }

    private static Substance substance(String root, String name) {
        return new Substance(root, root, name, name, "INGREDIENT", List.of(root, name));
    }

    /**
     * 三样都问到 —— 组合必须出现在结果里。
     * <p>
     * 保留原文共同服用的完整条件，不对任何子集是否安全下结论。
     */
    @Test
    void 三样都问到时组合注意事项出现在结果里() {
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        when(store.isAvailable()).thenReturn(true);
        when(store.resolveKeys(anyList())).thenReturn(java.util.Map.of("铁剂", "铁剂", "钙", "钙", "锌", "锌"));
        when(store.expandSubstances(anyList())).thenReturn(List.of(
                substance("铁剂", "铁剂"), substance("钙", "钙"), substance("锌", "锌")));
        when(store.risksOf(anyList())).thenReturn(List.of());
        when(store.combinationsOf(anyList())).thenReturn(List.of(combo("钙|铁剂|锌", "锌")));
        KnowledgeDocumentMapper docMapper = mock(KnowledgeDocumentMapper.class);
        when(docMapper.selectList(any())).thenReturn(List.of(sourceDocument("KB-0053", combo("钙|铁剂|锌", "锌").quote())));
        GraphService service = new GraphService(docMapper, mock(KnowledgeChunkMapper.class), store);

        var report = service.interactions(List.of("铁剂", "钙", "锌"));

        assertThat(report.available()).isTrue();
        assertThat(report.items())
                .as("三样都问到、组合的全部成员都在场，这条边必须出现")
                .flatExtracting(item -> item.risks())
                .anyMatch(e -> "COMBINED_WITH".equals(e.relation()));
    }

    /**
     * 只问到两样 —— 组合<b>不能</b>出现。
     * <p>
     * 组合节点只支持原文所列整组条件，不能把缺成员的输入当成完整组命中。
     * <b>按「相交」匹配就是把一条条件边当成无条件边</b> —— 这正是只加
     * {@code COMBINATION_RELATION} 到无向匹配里会犯的错。
     */
    @Test
    void 只问到组合的一部分时不报完整组注意事项() {
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        when(store.isAvailable()).thenReturn(true);
        when(store.resolveKeys(anyList())).thenReturn(java.util.Map.of("铁剂", "铁剂", "钙", "钙"));
        when(store.expandSubstances(anyList())).thenReturn(List.of(
                substance("铁剂", "铁剂"), substance("钙", "钙")));
        when(store.risksOf(anyList())).thenReturn(List.of());
        // 图谱里确实有这条组合，但用户只问了其中两样
        when(store.combinationsOf(anyList())).thenReturn(List.of());
        KnowledgeDocumentMapper docMapper = mock(KnowledgeDocumentMapper.class);
        when(docMapper.selectList(any())).thenReturn(List.of());
        GraphService service = new GraphService(docMapper, mock(KnowledgeChunkMapper.class), store);

        var report = service.interactions(List.of("铁剂", "钙"));

        assertThat(report.items())
                .as("缺一个成员就不成立：把条件边当无条件边会多报一条风险")
                .flatExtracting(item -> item.risks())
                .noneMatch(e -> "COMBINED_WITH".equals(e.relation()));
    }

    /**
     * 组合节点的键形状与「成员被全覆盖」这一层的判据。
     * <p>
     * {@code covers} 是私有方法，但它守的规则是公开约定：键以 {@code combo:} 开头、
     * 成员按 {@code |} 分。这里用一个形状不对的键当反例——
     * 图上真出现这种边时，放它过去等于报一条来历不明的风险。
     */
    @Test
    void 形状不对的组合键不算命中() {
        assertThat(KnowledgeGraphStore.combinationLabel("combo:维生素d|钙|铁剂"))
                .isEqualTo("维生素d + 钙 + 铁剂");
        assertThat(KnowledgeGraphStore.combinationLabel("维生素d")).isNull();
        assertThat(KnowledgeGraphStore.combinationLabel("combo:")).isNull();
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
