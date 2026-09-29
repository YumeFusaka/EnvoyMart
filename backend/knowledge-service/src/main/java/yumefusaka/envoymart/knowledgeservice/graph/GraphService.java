package yumefusaka.envoymart.knowledgeservice.graph;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.contract.GraphEdge;
import yumefusaka.envoymart.contract.GraphIngestPayload;
import yumefusaka.envoymart.contract.GraphIngestResult;
import yumefusaka.envoymart.contract.GraphNode;
import yumefusaka.envoymart.contract.GraphTriplePayload;
import yumefusaka.envoymart.contract.InteractionReport;
import yumefusaka.envoymart.contract.Substance;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeChunkEntity;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeDocumentEntity;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeChunkMapper;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeDocumentMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱的应用层：写入前的校验与落地、读取侧的查询组装。
 * <p>
 * <b>校验放在这里而不是抽取侧</b>（ai-service）：判定「引文是否真的有原文出处」
 * 必须拿着<b>事实源文档</b>比对，而这个仓库里文档的事实源是 {@code knowledge_document} 表。
 * ai-service 手上只有自己刚发出去的那份文本副本，用它做判据等于让提交者自己批改。
 */
@Slf4j
@Service
public class GraphService {

    /**
     * 单篇文档接受的关系条数上限。
     * <p>
     * 比抽取侧的 40 条宽，因为这一层要防的不是模型跑飞而是<b>调用方放大</b>：
     * 每条候选都要拿引文在正文里做一次全量扫描。宽出来的余量留给「抽取侧调大了上限、
     * 忘记同步这里」这种情况——那时这一层只该是兜底，不该成为常态下的瓶颈
     */
    private static final int MAX_TRIPLES_PER_DOC = 200;

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeGraphStore graphStore;

    public GraphService(KnowledgeDocumentMapper documentMapper,
                        KnowledgeChunkMapper chunkMapper,
                        KnowledgeGraphStore graphStore) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.graphStore = graphStore;
    }

    public KnowledgeGraphStore store() {
        return graphStore;
    }

    // ==================== 写入 ====================

    public GraphIngestResult ingest(GraphIngestPayload payload) {
        KnowledgeDocumentEntity doc = documentMapper.selectOne(Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                .eq(KnowledgeDocumentEntity::getDocNo, payload.getDocNo()));
        if (doc == null) {
            throw new IllegalArgumentException("图谱写入失败：知识库中没有文档编号 " + payload.getDocNo());
        }

        List<Triple> candidates = payload.getTriples() == null ? List.of()
                : payload.getTriples().stream().map(GraphService::toCandidate).toList();
        // 条数上限放在服务端而不是只放在抽取侧：抽取侧那个 40 条的上限保护的是模型跑飞，
        // 而这条内部接口是直连端口就能调的。每条候选都要拿引文在正文里做一次全量扫描，
        // 不设上限就是一个 CPU 放大口——文档正文越长，放大倍数越大
        if (candidates.size() > MAX_TRIPLES_PER_DOC) {
            throw new IllegalArgumentException("图谱写入失败：单篇文档的关系数 " + candidates.size()
                    + " 超过上限 " + MAX_TRIPLES_PER_DOC);
        }

        // 切片用来把引文偏移映射到具体是哪一片。取出来的是**当前**的切片——
        // 文档重切过之后旧偏移就不成立了，所以这一步必须在写入时做，不能缓存
        List<KnowledgeChunkEntity> chunks = chunkMapper.selectList(
                Wrappers.<KnowledgeChunkEntity>lambdaQuery()
                        .eq(KnowledgeChunkEntity::getDocId, doc.getId())
                        .orderByAsc(KnowledgeChunkEntity::getChunkIndex));

        TripleValidator.Result result = TripleValidator.validate(candidates, doc.getDocNo(),
                doc.getContent(), chunks);

        boolean available = graphStore.isAvailable();
        if (available) {
            graphStore.replaceDocument(doc.getDocNo(), result.accepted());
        } else {
            log.error("[Graph] 图谱不可用，文档 {} 的 {} 条关系未写入：{}",
                    doc.getDocNo(), result.accepted().size(), graphStore.unavailableReason());
        }
        log.info("[Graph] 文档 {} 抽取 {} 条，入库 {} 条，丢弃 {} 条（{}）",
                doc.getDocNo(), candidates.size(), result.accepted().size(), result.rejected(),
                result.breakdown());
        return GraphIngestResult.builder()
                .docNo(doc.getDocNo())
                .accepted(result.accepted().size())
                .rejected(result.rejected())
                .stored(available ? result.accepted().size() : 0)
                .available(available)
                .build();
    }

    private static Triple toCandidate(GraphTriplePayload t) {
        return new Triple(t.getHeadKind(), t.getHeadName(), t.getHeadLabel(), t.getRelation(),
                t.getTailKind(), t.getTailName(), t.getTailLabel(), t.getEffect(), t.getQuote());
    }

    /** 整批重建后清理已无文档支持的实体 */
    public void dropOrphans() {
        graphStore.dropOrphanEntities();
    }

    // ==================== 读取 ====================

    public List<GraphEdge> neighborhood(String name, int depth) {
        return withTitles(graphStore.neighborhood(name, depth));
    }

    public List<GraphNode> search(String keyword, int limit) {
        return graphStore.searchEntities(keyword, limit);
    }

    public Map<String, Object> stats() {
        return graphStore.stats();
    }

    /**
     * 「我手上这几样能不能一起吃」。
     * <p>
     * 分两段查而不是一条变长 Cypher 走到底，是为了让中间结果本身成为答案的一部分：
     * 第一段把商品拆成成分与营养素，第二段在这些物质上找风险关系。
     * 合起来写的话，界面上只有「有/没有风险」这一个结论，
     * 而用户最想知道的是「你为什么认为鱼油有问题」——那就是第一段的结果。
     */
    public InteractionReport interactions(List<String> inputs) {
        // 键用来查图（大小写与空白无关），原始写法留着回显。两者都要：
        // 只留键的话，调用方传 SPU7、拿回来是 spu7，就没法把它和自己问的那样东西对上号了
        Map<String, String> rawByKey = new LinkedHashMap<>();
        for (String raw : inputs == null ? List.<String>of() : inputs) {
            String key = TripleValidator.normalizeName(raw);
            if (!key.isEmpty()) {
                rawByKey.putIfAbsent(key, raw.strip());
            }
        }
        List<String> keys = List.copyOf(rawByKey.keySet());
        if (keys.isEmpty()) {
            return new InteractionReport(graphStore.isAvailable(), graphStore.unavailableReason(), List.of());
        }
        if (!graphStore.isAvailable()) {
            return new InteractionReport(false, graphStore.unavailableReason(), List.of());
        }

        List<Substance> substances = graphStore.expandSubstances(keys);
        Map<String, List<Substance>> byRoot = new LinkedHashMap<>();
        Set<String> allNames = new LinkedHashSet<>(keys);
        for (Substance s : substances) {
            byRoot.computeIfAbsent(s.rootName(), k -> new ArrayList<>()).add(s);
            allNames.add(s.name());
        }

        // 风险边在展开出的全部物质上找一次，再按「这条边的哪一端属于哪个 root」分发回去。
        // 逐 root 各查一次会重复扫同一批边，而一次查询的结果正好能按端点的归属拆开
        List<GraphEdge> risks = withTitles(graphStore.risksOf(List.copyOf(allNames)));

        List<InteractionReport.Item> items = new ArrayList<>(keys.size());
        for (String key : keys) {
            List<Substance> mine = byRoot.getOrDefault(key, List.of());
            Set<String> mineNames = new LinkedHashSet<>();
            mine.forEach(s -> mineNames.add(s.name()));
            // 每样东西自己也算——用户直接问药名时展开集合里只有它一个
            mineNames.add(key);

            // 一条边只保留一次。同一篇文档常常同时给出「深海鱼油与华法林」「EPA 与华法林」
            // 「DHA 与华法林」三条边，而对用户来说这是同一个结论、同一个出处，
            // 重复渲染成三行只会让人以为有三条不同的风险。
            // 键含 docId：不同文档各自支持这个结论时保留成两条，那是两份独立出处，不是重复
            Map<String, GraphEdge> unique = new LinkedHashMap<>();
            for (GraphEdge e : risks) {
                boolean headMine = mineNames.contains(e.head().name());
                boolean tailMine = mineNames.contains(e.tail().name());
                if (!headMine && !tailMine) {
                    continue;
                }
                // 头的这一端是我的，对方就是尾；反之亦然。
                // 保留 head/tail 的原始方向（CAUTION_FOR 的方向有语义），只用 counterpart 说明「哪边是对方」
                GraphEdge withCounterpart = e
                        .withChain(chainOf(mine, e))
                        .withCounterpart(headMine ? e.tail() : e.head());
                String dedupeKey = withCounterpart.counterpart().name() + '\u0000'
                        + withCounterpart.relation() + '\u0000' + withCounterpart.docId();
                unique.putIfAbsent(dedupeKey, withCounterpart);
            }
            List<GraphEdge> mineRisks = List.copyOf(unique.values());

            String raw = rawByKey.getOrDefault(key, key);
            String label = mine.isEmpty() ? raw : mine.get(0).rootLabel();
            items.add(new InteractionReport.Item(raw, label, !mine.isEmpty(), mine, mineRisks));
        }

        // 读的过程中图谱可能挂了：几个查询方法会各自吞掉异常并把可用性翻成 false。
        // 这里必须**再读一次**而不是直接写 true——否则报告会说「已检查、未发现风险」，
        // 而实际上一次都没查成，正好是这份报告最不能出的错
        if (!graphStore.isAvailable()) {
            return new InteractionReport(false, graphStore.unavailableReason(), List.of());
        }
        return new InteractionReport(true, null, items);
    }

    /**
     * 这条风险边是从哪条链上够到的 —— 取端点里第一个能被本 root 展开到的物质。
     * <p>
     * 找不到就返回空链：边的两端可能都属于别的 root（同一个物质被两样商品共同提供），
     * 硬塞一条无关的链会让答案看起来张冠李戴。
     */
    private static List<String> chainOf(List<Substance> mine, GraphEdge edge) {
        for (Substance s : mine) {
            if (s.name().equals(edge.head().name()) || s.name().equals(edge.tail().name())) {
                return s.chain();
            }
        }
        return List.of();
    }

    /**
     * 回填文档标题。
     * <p>
     * 图上只存 docNo（{@code KB-0010}）——那是稳定标识；标题是给人看的，
     * 而且会随版本变。一次查完再回填，不让每条边自己去查一次。
     */
    private List<GraphEdge> withTitles(List<GraphEdge> edges) {
        if (edges.isEmpty()) {
            return edges;
        }
        Set<String> docNos = new LinkedHashSet<>();
        edges.forEach(e -> {
            if (e.docId() != null) {
                docNos.add(e.docId());
            }
        });
        if (docNos.isEmpty()) {
            return edges;
        }
        Map<String, String> titles = new HashMap<>();
        documentMapper.selectList(Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                        .select(KnowledgeDocumentEntity::getDocNo, KnowledgeDocumentEntity::getTitle)
                        .in(KnowledgeDocumentEntity::getDocNo, docNos))
                .forEach(d -> titles.put(d.getDocNo(), d.getTitle()));
        return edges.stream().map(e -> e.withDocTitle(titles.get(e.docId()))).toList();
    }
}
