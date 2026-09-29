package yumefusaka.envoymart.aiservice.rag;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.graph.GraphRelation;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.Retriever;
import yumefusaka.envoymart.aiservice.client.KnowledgeClient;
import yumefusaka.envoymart.contract.GraphEdge;

import java.util.List;

/**
 * 图谱依据检索 —— 混合检索的<b>第三路</b>。
 * <p>
 * <b>它补的是文本检索够不着的那些问题。</b>语料里写着「深海鱼油与华法林合用可能增加出血风险」，
 * 用户问「SPU5 和华法林冲突吗」——{@code SPU5} 在<b>任何一篇文档里都不出现</b>，
 * BM25 与向量都无从下手。图谱知道 {@code spu5 → 深海鱼油}，于是那条风险边捞得回来。
 * <p>
 * <b>产出与知识库切片同构。</b>图上每条边自带 docId / chunkId / 偏移 / 逐字引文，
 * 于是这里能拼出一份与 {@code StructuralSplitter} 切出来的切片<b>长得一样</b>的依据：
 * {@code KnowledgePrompt} 照常渲染、引用照常标号、前端照常点回原文。
 * 如果这一路返回的是「图谱说 XX 和 YY 有冲突」这样一句渲染好的文本，
 * 那它在回答里就是一段无法追溯的断言——与模型自己编的话在界面上没有区别。
 * <p>
 * <b>失败一律降级为空列表。</b>这是增强路：图谱挂了，回答应当退化成纯文本检索的结果，
 * 而不是整个检索失败。但降级必须留痕——图谱长期不可用而没人发现，
 * 表现就是「这个功能好像没什么用」，与「它本来就没用」无从区分。
 */
@Slf4j
public class GraphEvidenceRetriever implements Retriever {

    /** 来源标识。与 manual / faq / policy 并列，审查回答来源时一眼能看出这条是推导来的 */
    /** 取值定义在 {@link DocumentChunk#SOURCE_GRAPH} —— 渲染层与拒答门都要读它，字符串只留一份 */
    static final String SOURCE = DocumentChunk.SOURCE_GRAPH;

    private final KnowledgeClient knowledgeClient;

    public GraphEvidenceRetriever(KnowledgeClient knowledgeClient) {
        this.knowledgeClient = knowledgeClient;
    }

    @Override
    public List<DocumentChunk> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<GraphEdge> edges;
        try {
            var response = knowledgeClient.recallGraph(query, Math.max(1, topK));
            if (response == null || response.getData() == null) {
                // 图谱不可用时 knowledge-service 回 503 业务码（HTTP 仍是 200，见项目约定），
                // 与「没有相关依据」在数据上都是空。这里不区分，但记一条 warn——
                // 区分它们要靠 /knowledge/graph/stats，不该在检索热路径上再查一次
                log.warn("[GraphRecall] 图谱召回未返回数据（可能不可用），本次退化为纯文本检索 query={}", query);
                return List.of();
            }
            edges = response.getData();
        } catch (RuntimeException e) {
            log.warn("[GraphRecall] 图谱召回失败，退化为纯文本检索：{}", e.getMessage());
            return List.of();
        }

        return edges.stream().map(GraphEvidenceRetriever::toChunk).toList();
    }

    /**
     * 一条边 → 一份依据。
     * <p>
     * 正文里带上「谁–什么关系–谁」这一行，而不是只放引文：引文往往只写「本品与华法林合用……」，
     * 而<b>「本品」是哪一品</b>这件事只在图上。少了这一行，重排器与模型都只看到一句
     * 不知道自己为什么被召回的话。
     */
    private static DocumentChunk toChunk(GraphEdge edge) {
        StringBuilder content = new StringBuilder("图谱推导：")
                .append(label(edge.head()))
                .append(' ').append(relationLabel(edge.relation())).append(' ')
                .append(label(edge.tail()));
        if (edge.effect() != null && !edge.effect().isBlank()) {
            content.append("，").append(edge.effect().strip());
        }
        content.append("\n原文：").append(edge.quote() == null ? "" : edge.quote().strip());

        return DocumentChunk.builder()
                .chunkId(chunkIdOf(edge))
                .docId(edge.docId())
                .title(edge.docTitle())
                .source(SOURCE)
                // position 留空：图谱边只知道自己落在哪一片，不知道那一片在文档里的层级路径
                // （{@code 《维生素D3说明书》 > 第二章 > 3.2}）。KnowledgePrompt 在 position
                // 为空时会退到《标题》，正是这里想要的落点。硬拼一个位置反而会让
                // 「点引用跳原文」跳到一个不存在的锚点
                .charOffset(edge.quoteStart())
                .content(content.toString())
                .build();
    }

    /**
     * 切片的身份。
     * <p>
     * <b>不能为 null。</b>RRF 融合是按 chunkId 归一的，键为空时所有无 id 的边会塌成同一个
     * 候选——表现是「图谱明明召回了好几条，回答里只多出一条依据」。用 文档号+偏移 兜底：
     * 同一篇文档里不同位置的两条边得到不同的键，而同一个位置的多条边合成一条，
     * 正好是「一片依据」该有的粒度。
     */
    private static String chunkIdOf(GraphEdge edge) {
        if (edge.chunkId() != null && !edge.chunkId().isBlank()) {
            return edge.chunkId();
        }
        return edge.docId() + "#" + edge.quoteStart();
    }

    private static String label(yumefusaka.envoymart.contract.GraphNode node) {
        if (node == null) {
            return "";
        }
        return node.label() != null && !node.label().isBlank() ? node.label() : String.valueOf(node.name());
    }

    /** 中文关系名归词表所有，在这里写 switch 的话，加一条关系必然漏掉某一边 */
    private static String relationLabel(String relation) {
        GraphRelation parsed = GraphRelation.parse(relation);
        return parsed == null ? String.valueOf(relation) : parsed.label();
    }
}
