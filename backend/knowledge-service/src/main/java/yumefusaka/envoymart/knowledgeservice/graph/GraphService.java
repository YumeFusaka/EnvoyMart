package yumefusaka.envoymart.knowledgeservice.graph;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import yumefusaka.envoymart.agent.graph.EntityKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.contract.GraphEdge;
import yumefusaka.envoymart.contract.GraphIngestPayload;
import yumefusaka.envoymart.contract.GraphIngestResult;
import yumefusaka.envoymart.contract.GraphNode;
import yumefusaka.envoymart.contract.GraphTriplePayload;
import yumefusaka.envoymart.contract.GraphBuildFailurePayload;
import yumefusaka.envoymart.contract.InteractionReport;
import yumefusaka.envoymart.contract.ProductGraphCoverage;
import yumefusaka.envoymart.contract.Substance;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeChunkEntity;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeDocumentEntity;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeChunkMapper;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeDocumentMapper;
import yumefusaka.envoymart.knowledgeservice.mapper.GraphBuildFailureMapper;
import yumefusaka.envoymart.knowledgeservice.entity.GraphBuildFailureEntity;
import java.time.LocalDateTime;

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
    private final GraphBuildFailureMapper failureMapper;

    @Autowired
    public GraphService(KnowledgeDocumentMapper documentMapper,
                        KnowledgeChunkMapper chunkMapper,
                        KnowledgeGraphStore graphStore, GraphBuildFailureMapper failureMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.graphStore = graphStore;
        this.failureMapper = failureMapper;
    }

    /** 兼容只读查询单测；失败记录仅在写入路径需要。 */
    public GraphService(KnowledgeDocumentMapper documentMapper,
                        KnowledgeChunkMapper chunkMapper,
                        KnowledgeGraphStore graphStore) {
        this(documentMapper, chunkMapper, graphStore, null);
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
        String batchId = payload.getBatchId() == null || payload.getBatchId().isBlank()
                ? "legacy-" + System.currentTimeMillis() : payload.getBatchId();
        result.rejectedTriples().forEach(rejected -> recordFailure(batchId, doc.getDocNo(),
                rejected, "VALIDATION", rejected.reason().name(), rejected.detail(), false,
                doc.getContent(), chunks));

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

    public void recordFailure(String batchId, String docNo, String entityKey, String stage,
                              String reasonCode, String detail, boolean retryable) {
        recordFailure(batchId, docNo, new GraphBuildFailureEntity(), stage, reasonCode,
                detail, retryable, entityKey);
    }

    public void recordFailure(GraphBuildFailurePayload payload) {
        GraphBuildFailureEntity entity = new GraphBuildFailureEntity();
        entity.setRawCandidate(normalize(payload.getRawCandidate()));
        entity.setNormalizedHeadKind(normalize(payload.getNormalizedHeadKind()));
        entity.setNormalizedHead(normalize(payload.getNormalizedHead()));
        entity.setNormalizedTailKind(normalize(payload.getNormalizedTailKind()));
        entity.setNormalizedTail(normalize(payload.getNormalizedTail()));
        entity.setRelation(normalize(payload.getRelation()));
        entity.setQuote(trim(payload.getQuote(), 1000));
        entity.setQuoteOffsetStart(payload.getQuoteOffsetStart());
        entity.setQuoteOffsetEnd(payload.getQuoteOffsetEnd());
        entity.setAliasHit(payload.getAliasHit());
        entity.setChunkId(normalize(payload.getChunkId()));
        recordFailure(payload.getBatchId(), payload.getDocNo(), entity, payload.getStage(),
                payload.getReasonCode(), payload.getDetail(), payload.isRetryable(), payload.getEntityKey());
    }

    private void recordFailure(String batchId, String docNo, TripleValidator.RejectedTriple rejected,
                               String stage, String reasonCode, String detail, boolean retryable,
                               String sourceContent, List<KnowledgeChunkEntity> chunks) {
        String entityKey = rejected == null ? null : rejected.head();
        GraphBuildFailureEntity entity = new GraphBuildFailureEntity();
        entity.setRawCandidate(rejected == null ? null : candidateText(rejected));
        entity.setNormalizedHeadKind(rejected == null ? null : normalize(rejected.headKind()));
        entity.setNormalizedHead(rejected == null ? null : canonical(rejected.head()));
        entity.setNormalizedTailKind(rejected == null ? null : normalize(rejected.tailKind()));
        entity.setNormalizedTail(rejected == null ? null : canonical(rejected.tail()));
        entity.setRelation(rejected == null ? null : normalize(rejected.relation()));
        entity.setQuote(rejected == null ? null : trim(rejected.quote(), 1000));
        if (rejected != null && rejected.quote() != null && !rejected.quote().isBlank()) {
            int start = sourceContent == null ? -1 : sourceContent.indexOf(rejected.quote());
            if (start >= 0) {
                entity.setQuoteOffsetStart(start);
                entity.setQuoteOffsetEnd(start + rejected.quote().length());
                entity.setChunkId(chunkIdAt(chunks, start));
            }
            String head = normalize(rejected.head());
            String tail = normalize(rejected.tail());
            entity.setAliasHit(!canonical(head).equals(head) || !canonical(tail).equals(tail));
        }
        recordFailure(batchId, docNo, entity, stage, reasonCode, detail, retryable, entityKey);
    }

    private void recordFailure(String batchId, String docNo, GraphBuildFailureEntity entity,
                               String stage, String reasonCode, String detail, boolean retryable,
                               String entityKey) {
        entity.setBatchId(batchId == null || batchId.isBlank() ? "unknown" : batchId);
        entity.setDocNo(docNo);
        entity.setEntityKey(entityKey);
        entity.setStage(normalizeStage(stage));
        entity.setReasonCode(normalizeReason(reasonCode));
        String safeDetail = detail == null ? "" : detail.replaceAll("[\\r\\n\\t]", " ");
        entity.setDetail(safeDetail.substring(0, Math.min(safeDetail.length(), 500)));
        entity.setRetryable(retryable);
        entity.setOccurredAt(LocalDateTime.now());
        if (failureMapper != null) {
            failureMapper.insert(entity);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String canonical(String value) {
        String normalized = TripleValidator.normalizeName(value);
        return normalized == null || normalized.isBlank() ? normalized : TripleValidator.canonicalName(normalized);
    }

    private static String trim(String value, int max) {
        String normalized = value == null ? null : value.strip();
        return normalized == null ? null : normalized.substring(0, Math.min(max, normalized.length()));
    }

    private static String candidateText(TripleValidator.RejectedTriple rejected) {
        return rejected.headKind() + " " + rejected.head() + " -" + rejected.relation()
                + "-> " + rejected.tailKind() + " " + rejected.tail();
    }

    private String normalizeStage(String stage) {
        if (stage == null || stage.isBlank()) return "UNKNOWN";
        return switch (stage.toUpperCase()) {
            case "EXTRACT", "NORMALIZE", "LINK", "RELATION_VALIDATE", "WRITE", "VALIDATION", "CATALOG" -> stage.toUpperCase();
            default -> "UNKNOWN";
        };
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) return "UNKNOWN";
        return reason.toUpperCase().replace('-', '_').replace(' ', '_');
    }

    public List<GraphBuildFailureEntity> failures(String batchId, String docNo, String stage,
                                                   String reasonCode, int limit) {
        var query = Wrappers.<GraphBuildFailureEntity>lambdaQuery()
                .orderByDesc(GraphBuildFailureEntity::getOccurredAt)
                .last("limit " + Math.clamp(limit, 1, 5000));
        if (batchId != null && !batchId.isBlank()) query.eq(GraphBuildFailureEntity::getBatchId, batchId);
        if (docNo != null && !docNo.isBlank()) query.eq(GraphBuildFailureEntity::getDocNo, docNo);
        if (stage != null && !stage.isBlank()) query.eq(GraphBuildFailureEntity::getStage, stage);
        if (reasonCode != null && !reasonCode.isBlank()) query.eq(GraphBuildFailureEntity::getReasonCode, reasonCode);
        if (failureMapper == null) return List.of();
        List<GraphBuildFailureEntity> rows = failureMapper.selectList(query);
        rows.forEach(row -> {
            if ("候选三元组未通过图谱事实校验".equals(row.getDetail())) {
                row.setDetail("历史记录未保存候选字段；拒绝原因：" + failureReason(row.getReasonCode()));
            }
        });
        return rows;
    }

    private String failureReason(String reasonCode) {
        return switch (reasonCode == null ? "" : reasonCode) {
            case "VOCABULARY" -> "关系或端点类型不符合封闭词表";
            case "UNANCHORED" -> "端点、组合成员或引文没有被正文逐字锚定";
            case "UNGROUNDED" -> "quote 没有在正文中逐字命中";
            case "MALFORMED" -> "候选结构或实体形状不合法";
            case "SELF_LOOP" -> "两端归一后是同一实体";
            default -> "候选未通过事实校验";
        };
    }

    public GraphBuildFailureEntity failure(long id) {
        return failureMapper == null ? null : failureMapper.selectById(id);
    }

    public Map<String, Object> failureStats(String batchId) {
        List<GraphBuildFailureEntity> rows = allFailures(batchId);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("failed", rows.size());
        stats.put("retryable", rows.stream().filter(GraphBuildFailureEntity::getRetryable).count());
        stats.put("batches", rows.stream().map(GraphBuildFailureEntity::getBatchId).distinct().count());
        Map<String, Long> byStage = rows.stream().collect(java.util.stream.Collectors.groupingBy(
                row -> row.getStage() == null ? "UNKNOWN" : row.getStage(), LinkedHashMap::new,
                java.util.stream.Collectors.counting()));
        Map<String, Long> byReason = rows.stream().collect(java.util.stream.Collectors.groupingBy(
                row -> row.getReasonCode() == null ? "UNKNOWN" : row.getReasonCode(), LinkedHashMap::new,
                java.util.stream.Collectors.counting()));
        stats.put("byStage", byStage);
        stats.put("byReason", byReason);
        stats.put("status", rows.isEmpty() ? "NO_FAILURES_RECORDED" : "FAILED");
        return stats;
    }

    private List<GraphBuildFailureEntity> allFailures(String batchId) {
        if (failureMapper == null) return List.of();
        var query = Wrappers.<GraphBuildFailureEntity>lambdaQuery()
                .orderByDesc(GraphBuildFailureEntity::getOccurredAt);
        if (batchId != null && !batchId.isBlank()) query.eq(GraphBuildFailureEntity::getBatchId, batchId);
        return failureMapper.selectList(query);
    }

    private static String chunkIdAt(List<KnowledgeChunkEntity> chunks, int offset) {
        if (chunks == null || chunks.isEmpty()) return null;
        KnowledgeChunkEntity best = null;
        for (KnowledgeChunkEntity chunk : chunks) {
            if (chunk.getCharOffset() == null || chunk.getCharOffset() > offset) continue;
            if (best == null || chunk.getCharOffset() > best.getCharOffset()) best = chunk;
        }
        return best == null ? null : best.getChunkId();
    }

    private static Triple toCandidate(GraphTriplePayload t) {
        return new Triple(t.getHeadKind(), t.getHeadName(), t.getHeadLabel(), t.getRelation(),
                t.getTailKind(), t.getTailName(), t.getTailLabel(), t.getEffect(), t.getQuote(),
                Boolean.TRUE.equals(t.getDeclared()));
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

        // 组合禁忌单独查一次，且**只挂在「这次把所有成员都问到了」的那一项上**。
        // 它不能像单跳风险那样对每个 root 各分发一遍：组合的成立条件是「这几样同时在场」，
        // 挂到其中某一样上会变成「单吃这个就有事」，而那样的结论在图上根本不存在。
        // 分配规则见 combinationsFor
        List<GraphEdge> combinations = allNames.isEmpty() ? List.of()
                : withTitles(graphStore.combinationsOf(List.copyOf(allNames)));

        List<InteractionReport.Item> items = new ArrayList<>(inputs0.size());
        for (String input : inputs0) {
            String raw = rawByKey.get(input);
            String key = resolved.get(input);
            if (key == null) {
                // 图外实体兜底：解析不到不代表图上没有相近的东西，可能只是叫法不同
                // （用户说「鱼油」、节点叫「深海鱼油」）。带上「最近实体」让答案从
                // 「没有收录」变成「没有收录，你是不是想问这个」——前者会让用户以为
                // 图谱里查不到任何相关信息，后者才给了下一步动作。
                // **只提示、不代答**：拿一个猜出来的实体去跑相互作用，等于把
                // 「像它」当成「是它」，那会给出一个张冠李戴的风险结论
                String nearHint = graphStore.nearestEntity(input)
                        .map(KnowledgeGraphStore.NearestEntity::label)
                        .orElse(null);
                items.add(new InteractionReport.Item(raw, raw, false, List.of(), List.of(), nearHint));
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
            List<GraphEdge> mineRisks = new ArrayList<>(unique.values());
            // 组合禁忌：挂在这一项上，条件是「这一项参与了某个成立的组合」。
            // <b>判据是该组合的成员里至少有一个属于这一项</b>，而不是「全部属于」——
            // 组合天生跨项：{@code A+B+C} 的三条成员分属三次输入，任何单独一项都装不下它。
            // 按「全部属于这一项」判会让所有跨项组合永远挂不上来（这正是第一版的表现：
            // 三样都问了，报告里一条组合也没有，而日志一切正常）。
            //
            // 三项都挂同一个组合、chain 里都写着完整成员，读起来是「A+B+C 一起有问题」——
            // 这不是重复渲染：用户逐项核对时会发现每一项都指向同一个组合结论，
            // 而那正是他该得到的印象（不是 A 单独有事、也不是 B 单独有事）
            // 并把组合成员写进 chain —— 用户要看的正是「哪几样凑在一起才出的这件事」
            mineRisks.addAll(combinationsFor(mineNames, combinations));

            // 回答里的名字用**用户自己的写法**（raw），不用图谱里的键：他问的是
            // 「鱼油软胶囊」，回一句「spu5 没有风险」既对不上号也看不懂
            String label = mine.isEmpty() ? raw : mine.get(0).rootLabel();
            items.add(new InteractionReport.Item(raw, label, !mine.isEmpty(), mine, mineRisks, null));
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
        // 组合禁忌也算风险依据：用户这一句话里同时提到了某组合的全部成员时，
        // 那条「几样一起才有事」的边正是他该看到的。成员的判定用 scope（含展开出的物质），
        // 与 interactions 同源 —— 两处判据各写一份的话，「问法不同结论不同」只是时间问题
        edges.addAll(graphStore.combinationsOf(List.copyOf(scope)));
        if (edges.isEmpty()) {
            return List.of();
        }

        // 同一个切片上前几条边只是同一句话支撑的不同三元组，对检索而言是一片依据。
        // 去重放在这里而不是让调用方做：切片粒度是这一层的概念
        //
        // <b>「有边返回」不等于「用户问的那件事有依据」。</b>实体链接是按名字做子串匹配，
        // 用户问「K2 和鱼油能一起吃吗」时命中的是<b>鱼油</b>，返回的全是鱼油自己的边——
        // 一条都不涉及 K2。把这种结果当成「有图谱依据」提级，等于把「K2 未收录」
        // 说成「K2 有图谱支撑」。所以这里与 interactions 那条路径用同一个判据：
        // <b>边的至少一端必须能从用户链接到的实体（或其展开物质）到达</b>，
        // 并把这条可达链写进 chain，让下游看得见「为什么这条边和用户有关」。
        Map<String, GraphEdge> unique = new LinkedHashMap<>();
        for (GraphEdge e : edges) {
            List<String> chain = chainOf(substances, e);
            boolean reachableFromLinked = !chain.isEmpty()
                    || linked.contains(e.head().name()) || linked.contains(e.tail().name());
            if (!reachableFromLinked) {
                continue;
            }
            unique.putIfAbsent(e.docId() + '\u0000' + e.chunkId() + '\u0000' + e.relation(),
                    chain.isEmpty() ? e : e.withChain(chain));
        }
        return withTitles(List.copyOf(unique.values())).stream()
                .limit(Math.max(1, limit))
                .toList();
    }

    /**
     * 找出「成员全在本项展开集合里」的组合，并把成员名填进 chain。
     * <p>
     * {@code combinationsOf} 已经按「本次请求的全部实体」筛过一遍，这里再做一次
     * 逐项过滤：一次问三样东西时，某个组合可能只用到其中两样的成分，
     * 那它该挂在那两样上，而不是三样各挂一次（后者会让用户以为第三样也参与了）。
     * <p>
     * {@code chain} 填成组合成员的中文名 —— 前端画这条边时就能显示
     * 「铁剂 + 钙剂 → 某药物」，而不是一个不知从哪冒出来的组合节点。
     */
    private static List<GraphEdge> combinationsFor(Set<String> mineNames, List<GraphEdge> combinations) {
        if (combinations.isEmpty()) {
            return List.of();
        }
        List<GraphEdge> out = new ArrayList<>();
        for (GraphEdge e : combinations) {
            List<String> members = combinationMembersOf(e.head().name());
            // 成员与这一项有交集即挂上，理由见调用处 
            if (members.isEmpty() || java.util.Collections.disjoint(members, mineNames)) {
                continue;
            }
            out.add(e.withChain(members));
        }
        return out;
    }

    /** 组合节点键 → 成员键列表。键形状不对时返回空列表（调用方据此跳过该边） */
    private static List<String> combinationMembersOf(String key) {
        if (!EntityKind.isCombinationKey(key)) {
            return List.of();
        }
        String members = key.substring(EntityKind.COMBINATION_PREFIX.length());
        if (members.isBlank()) {
            return List.of();
        }
        return List.of(members.split("\\|"));
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
     * 某个商品在图上被哪些文档支持 —— 「这个商品的说明书接上了没有」。
     * <p>
     * <b>为什么要单独有这个方法，而不是让前端自己拉邻域再挑。</b>商品与说明书在库里
     * <b>没有外键</b>，两者的绑定是构建期实体链接的结果。管理台要回答「我给它传的说明书
     * 到底接上没有」，唯一的事实来源就是图上这个商品节点的边——拉到邻域、按 docId 去重，
     * 就是它当前的文档覆盖。前端各写一遍这段聚合，两边口径迟早会分叉。
     * <p>
     * 只取一跳且只认 {@code CONTAINS}：这份视图的问题是「有没有说明书」，
     * 不是「它连着什么」。走深了会把「别人的说明书里顺带提到这个成分」也算成本商品的文档。
     *
     * @param spuKey 商品键，形如 {@code spu7}（大小写与空白不敏感）
     * @return 覆盖该商品的文档，按文档号升序；每篇带支持它的关系条数
     */
    public List<ProductDocRef> documentsOfProduct(String spuKey) {
        List<GraphEdge> edges = neighborhood(spuKey, 1);
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        for (GraphEdge edge : edges) {
            if (!"CONTAINS".equals(edge.relation()) || edge.docId() == null) {
                continue;
            }
            counts.computeIfAbsent(edge.docId(), k -> new int[1])[0]++;
            if (edge.docTitle() != null) {
                titles.put(edge.docId(), edge.docTitle());
            }
        }
        return counts.entrySet().stream()
                .map(e -> new ProductDocRef(e.getKey(), titles.get(e.getKey()), e.getValue()[0]))
                .sorted(java.util.Comparator.comparing(ProductDocRef::docNo))
                .toList();
    }

    /**
     * 全量在售商品的图谱覆盖读数 —— 「多少商品是有资料的、多少没有」。
     * <p>
     * <b>为什么口径落在这里而不是管理台。</b>「一个商品有没有被资料支持」这件事，
     * 判据只有图上那几条 {@code CONTAINS} 边。管理台、ai-service 各写一遍这段判据，
     * 就会像「价格筛选」那次一样，两条路在边界条件下给出相反的答案。
     * <b>本方法必须与 {@link #documentsOfProduct} 用同一套判据</b>：
     * 只认一跳、只认 {@code CONTAINS}、按 docId 去重。
     * <p>
     * <b>为什么商品清单由调用方给。</b>商品目录的事实源在 product-service，
     * 那边才是「哪些商品在售」的唯一判据（下架商品不该出现在覆盖率里）。
     * knowledge-service 不持有商品目录，也不该为了这一件事去反向依赖它。
     *
     * @param spus 在售商品清单，只用到「键」与「展示名」
     * @return 覆盖率读数。图谱不可用时 {@code available=false}，
     *         调用方必须把「没查成」与「全都覆盖了」分开
     */
    public ProductGraphCoverage coverage(List<SpuRef> spus) {
        if (spus == null || spus.isEmpty()) {
            return new ProductGraphCoverage(0, 0, List.of(), true, null);
        }
        // 查图的键与存储层的节点键必须是同一套规范化（图上存 spu7，调用方给 SPU7），
        // 查回来也用同一套键取值。这两处少任何一处，覆盖率都会恒为 0
        Map<String, KnowledgeGraphStore.ProductCoverageView> views = graphStore.productCoverage(
                spus.stream().map(SpuRef::spuKey).toList());
        if (!graphStore.isAvailable()) {
            // 图谱不可用时**不能**把全部商品算成未覆盖：那会让人以为要重传几十份说明书，
            // 而真实故障是图库连不上。这一条判据与其它图谱接口一致
            return new ProductGraphCoverage(spus.size(), 0, List.of(), false, graphStore.unavailableReason());
        }
        List<ProductGraphCoverage.Uncovered> uncovered = new ArrayList<>();
        int covered = 0;
        for (SpuRef spu : spus) {
            KnowledgeGraphStore.ProductCoverageView view =
                    views.get(TripleValidator.normalizeName(spu.spuKey()));
            if (view == null) {
                // 查询里 UNWIND 过每一个键，理论上不会缺；真缺了说明图在两次查询之间变过，
                // 保守地当「没节点」——它至少把商品列进了欠账清单，不会被静默算成已覆盖
                uncovered.add(new ProductGraphCoverage.Uncovered(spu.spuKey(), spu.name(),
                        ProductGraphCoverage.Reason.NO_NODE));
            } else if (view.docCount() > 0) {
                covered++;
            } else {
                uncovered.add(new ProductGraphCoverage.Uncovered(spu.spuKey(), spu.name(),
                        view.nodeExists() ? ProductGraphCoverage.Reason.NO_DOCUMENT
                                : ProductGraphCoverage.Reason.NO_NODE));
            }
        }
        return new ProductGraphCoverage(spus.size(), covered, uncovered, true, null);
    }

    /**
     * 覆盖率计算用的商品引用 —— 只带「键」与「给人看的名字」。
     * <p>
     * 不直接用 {@code ProductSummary}：knowledge-service 拿不到也不该拿商品契约，
     * 它需要的只是「有哪些键要查、查完用哪个名字回显」。
     */
    public record SpuRef(String spuKey, String name) {
    }
    /**
     * 一个商品当前的文档覆盖。
     *
     * @param docNo   文档编号
     * @param title   文档标题。文档被停用后标题仍要显示，所以不在这里过滤状态
     * @param relations 该文档在这个商品上的关系条数 —— 0 表示文档里提到过但没抽出可用关系
     */
    public record ProductDocRef(String docNo, String title, int relations) {
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
