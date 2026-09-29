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
            // 归一到规范名再当键：用户说「富马酸亚铁」而图上的节点叫「铁剂」时它们是同一个东西。
            // 不归一的话，同一次请求里同时问这两个会被当成两样，而且**两样都解析不到节点**
            // （图上没有「富马酸亚铁」这个键），结果双双报「没有收录」——两次错误回答
            String key = TripleValidator.canonicalName(TripleValidator.normalizeName(raw));
            if (!key.isEmpty()) {
                rawByKey.putIfAbsent(key, raw.strip());
            }
        }
        List<String> inputs0 = List.copyOf(rawByKey.keySet());
        if (inputs0.isEmpty()) {
            return new InteractionReport(graphStore.isAvailable(), graphStore.unavailableReason(), List.of());
        }
        if (!graphStore.isAvailable()) {
            return new InteractionReport(false, graphStore.unavailableReason(), List.of());
        }

        // 用户嘴里说的是商品名，图上商品的键是 SPU 编号——这一步是那座桥。
        // 放在查询之前而不是查询之后：拿着一个图上不存在的键去展开，回来的必然只有
        // 「没收录」这一个结论，而那正是本方法最不该给出的错误答案
        Map<String, String> resolved = graphStore.resolveKeys(inputs0);

        // 解析失败的项不参与查询，但**必须在结果里逐项出列**：用户在界面上看不到
        // 自己问的那样东西，会以为系统把它漏了，而正确的说法是「图谱里没有收录它」
        List<String> keys = resolved.values().stream().distinct().toList();

        List<Substance> substances = keys.isEmpty() ? List.of() : graphStore.expandSubstances(keys);
        Map<String, List<Substance>> byRoot = new LinkedHashMap<>();
        Set<String> allNames = new LinkedHashSet<>(keys);
        for (Substance s : substances) {
            byRoot.computeIfAbsent(s.rootName(), k -> new ArrayList<>()).add(s);
            allNames.add(s.name());
        }

        // 风险边在展开出的全部物质上找一次，再按「这条边的哪一端属于哪个 root」分发回去。
        // 逐 root 各查一次会重复扫同一批边，而一次查询的结果正好能按端点的归属拆开
        List<GraphEdge> risks = allNames.isEmpty() ? List.of()
                : withTitles(graphStore.risksOf(List.copyOf(allNames)));

        List<InteractionReport.Item> items = new ArrayList<>(inputs0.size());
        for (String input : inputs0) {
            String raw = rawByKey.get(input);
            String key = resolved.get(input);
            if (key == null) {
                items.add(new InteractionReport.Item(raw, raw, false, List.of(), List.of()));
                continue;
            }

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

            // 回答里的名字用**用户自己的写法**（raw），不用图谱里的键：他问的是
            // 「鱼油软胶囊」，回一句「spu5 没有风险」既对不上号也看不懂
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
     * 图谱召回 —— 从一段自由文本出发，取出图上与它有关的<b>依据边</b>。
     * <p>
     * <b>这是文本检索够不着的那条路。</b>语料里写着「深海鱼油与华法林合用可能增加出血风险」，
     * 而用户问的是「SPU5 和华法林冲突吗」——{@code SPU5} 这三个字符在<b>任何一篇文档里都不出现</b>，
     * BM25 与向量都无从下手。图谱知道 {@code spu5 → 深海鱼油}，于是那条风险边捞得回来。
     * <p>
     * 返回边而不是渲染好的文本：图上的一条边<b>自带完整出处</b>
     * （docId / chunkId / 偏移 / 逐字引文），调用方拿到它就能拼出与知识库切片
     * 同构的一份依据，两路证据在回答里长得一样、点得回原文。返回文本的话
     * 这一层就要开始管措辞，而措辞属于回答侧。
     * <p>
     * 失败一律返回空列表、不抛异常：这是<b>增强路</b>，图谱挂了不该让整个回答失败。
     * 但「挂了」与「没有相关依据」在调用方看来都是空列表——所以可用性由
     * {@link #stats()} 与 {@link #interactions} 那条路径如实外露，检索侧不重复报。
     */
    public List<GraphEdge> recall(String query, int limit) {
        if (!graphStore.isAvailable() || query == null || query.isBlank()) {
            return List.of();
        }
        List<String> linked = graphStore.linkEntities(query);
        if (linked.isEmpty()) {
            return List.of();
        }

        // 与相互作用查询同一套展开：用户说的可能是商品，而依据挂在成分或营养素上
        List<Substance> substances = graphStore.expandSubstances(linked);
        Set<String> scope = new LinkedHashSet<>(linked);
        substances.forEach(s -> scope.add(s.name()));

        // 两类边都要：风险边是结论，组成边是「为什么这个东西和那个东西有关」。
        // 只给风险边的话，用户会看到一条关于「深海鱼油」的警告，
        // 而他从没提过深海鱼油——看起来像答非所问。
        //
        // **组成边排前面**，因为下面会按 limit 截断：它的条数等于用户点到的商品数
        // （只有 PRODUCT 作头的边才是组成边），天然就少；而风险边可能几十条。
        // 反过来排的话，一次「SPU7 有什么禁忌」正好会在第 5 条上把那条唯一的桥截掉，
        // 剩下的全是没有来路的警告——正是这段话开头说的那种答非所问
        List<GraphEdge> edges = new ArrayList<>(graphStore.compositionOf(linked));
        edges.addAll(graphStore.risksOf(List.copyOf(scope)));
        if (edges.isEmpty()) {
            return List.of();
        }

        // 同一个切片上前几条边只是同一句话支撑的不同三元组，对检索而言是一片依据。
        // 去重放在这里而不是让调用方做：切片粒度是这一层的概念
        Map<String, GraphEdge> unique = new LinkedHashMap<>();
        for (GraphEdge e : edges) {
            unique.putIfAbsent(e.docId() + '\u0000' + e.chunkId() + '\u0000' + e.relation(), e);
        }
        return withTitles(List.copyOf(unique.values())).stream()
                .limit(Math.max(1, limit))
                .toList();
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
