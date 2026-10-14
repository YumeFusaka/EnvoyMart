package yumefusaka.envoymart.aiservice.knowledge;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.graph.EntityAliases;
import yumefusaka.envoymart.agent.graph.EntityKind;
import yumefusaka.envoymart.agent.graph.EntityNames;
import yumefusaka.envoymart.agent.graph.GraphRelation;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.aiservice.client.KnowledgeClient;
import yumefusaka.envoymart.aiservice.client.ProductClient;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.GraphIngestPayload;
import yumefusaka.envoymart.contract.GraphIngestResult;
import yumefusaka.envoymart.contract.GraphTriplePayload;
import yumefusaka.envoymart.contract.ProductSummary;
import yumefusaka.envoymart.contract.GraphBuildFailurePayload;
import java.util.UUID;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 知识图谱的构建 —— 逐篇抽取三元组，交给 knowledge-service 校验入库。
 * <p>
 * <b>为什么抽取在 ai-service 而不在 knowledge-service</b>：模型只在这一侧装配。
 * knowledge-service 连 ChatModel 都没有（它依赖 agent-core 只是为了复用切分与分词），
 * 让它去调模型会把一个纯粹的存储 + 检索服务变成需要外网依赖的服务。
 * 分工因此是：<b>这一侧负责「读懂」，那一侧负责「判真」</b>——校验必须有原文，
 * 而原文的事实源在 knowledge-service 的库里。
 * <p>
 * <b>三条硬约束，都是「模型看起来说得对」会造成的真实错误</b>：
 * <ol>
 *   <li><b>只抽原文写了的</b>。模型当然知道华法林不能和鱼油乱吃，但图上不该出现一条
 *       没有原文出处的边——演示时点开引用会是空的，而空引用比没有这条边更糟，
 *       它看起来像有依据。</li>
 *   <li><b>商品必须落到目录里的真实 SPU</b>。模型输出的商品键在这份对照表里找不到，
 *       整条三元组丢弃，不留一个「看起来像商品名」的孤儿节点。</li>
 *   <li><b>抽取失败不发请求</b>。knowledge-service 的写入语义是「整体替换」，
 *       发一个空列表等于把这篇文档已经建好的边全部清空。</li>
 * </ol>
 */
@Slf4j
public class KnowledgeGraphBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单篇文档送进模型的字符上限。种子语料最长约 1500 字，这个上限是防意外的护栏 */
    private static final int MAX_DOC_CHARS = 12000;
    /** 单篇的关系条数上限，防止模型跑飞时灌进几百条 */
    private static final int MAX_TRIPLES = 40;
    /** 只识别肯定的组成声明；“不含”“与其他含某成分制剂同服”不能证明本品组成。 */
    private static final Pattern COMPOSITION_DECLARATION = Pattern.compile(
            "^(?:本品|本产品)(?:主要成分为|成分为|所含|含有?|包含)");
    private static final Pattern COMPOSITION_FIELD = Pattern.compile(
            "^(?:主要成分|成分)(?:为|[:：])");
    private static final Pattern UNIT_COMPOSITION = Pattern.compile(
            "^(?:(?:本品|本产品)(?:规格(?:为|[:：]))?|规格(?:为|[:：]))?"
                    + "每(?:一包|片|粒|袋|支|份|瓶|滴)(?:\\d+(?:\\.\\d+)?(?:毫升|毫克|克))?(?:含有?|包含)");
    /**
     * 商品名锚点的最短公共子串。目录里的名字是「褪黑素缓释片」，而正文只写「缓释片」——
     * 锚点靠子串识别，这个长度是「够具体、不至于在别的商品名里撞车」的下界。
     */
    private static final int ANCHOR_MIN_CHARS = 2;
    /** 组成声明关键词。只有带它的子句才贡献成分，见 multiSubjectCompositions */
    private static final Pattern COMPOSITION_KEYWORD = Pattern.compile("含|包含|成分为|主要成分|配方|添加");
    /** 能定位到切片的最短引文长度，与 knowledge-service 的 MIN_QUOTE_CHARS 一致 */
    private static final int LOCATABLE_QUOTE_CHARS = 6;
    private static final Pattern COMPOSITION_CONTINUATION = Pattern.compile(
            "^(?:主要成分为|成分为|含有?|包含|并添加|添加)");
    /**
     * 抽取输出的 Token 上限。
     * <p>
     * <b>不能沿用对话侧的默认值</b>。对话默认 2048，而抽取的每一条三元组
     * 都要带一段原文引文，十几条就能把它顶满——顶满的后果不是少几条，
     * 而是 JSON 在<b>字符串中间被截断</b>，整篇文档一条都解析不出来。
     * 实测 KB-0010 就是这样：模型明确抽到了「维生素D 与噻嗪类利尿剂」这条关系，
     * 输出被截在第 2048 个 Token 上，全篇归零。
     */
    private static final int EXTRACT_MAX_TOKENS = 4096;
    /**
     * 连续失败到这个数就中止整批。
     * <p>
     * 单篇失败是常态（一篇文档抽不出来，或者模型抖一下），不拖垮整批。
     * 但连续失败说明模型侧或图谱侧整体不可用，这时再挨个试下去只会把同一条错误
     * 重复十几行、每次还都要等满一次超时（模型 60 秒、驱动重试 30 秒），
     * 而每一篇的结论都一样，没有新信息。
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final LLMProvider llmProvider;
    private final LLMConfig defaultConfig;
    private final KnowledgeClient knowledgeClient;
    private final ProductClient productClient;

    public KnowledgeGraphBuilder(LLMProvider llmProvider, LLMConfig defaultConfig,
                                 KnowledgeClient knowledgeClient, ProductClient productClient) {
        this.llmProvider = llmProvider;
        this.defaultConfig = defaultConfig;
        this.knowledgeClient = knowledgeClient;
        this.productClient = productClient;
    }

    /**
     * 整批构建的结果，供调用方在日志与接口里说清「建了多少」。
     *
     * @param accepted 校验通过的条数。**不是落到图上的条数**，用它当「已建好」会读错
     * @param stored   真正写进图的条数。图谱不可用时 accepted 有一堆而 stored 是 0
     * @param failed   既含「抽取失败」也含「写入失败」——两者都不该计入入库数，
     *                 但日志里各自有一条 WARN 说明是哪一种。
     *                 <b>不要把它读成「抽取失败」</b>：实测踩过一次，写入侧读超时被记成
     *                 「抽取失败 1 篇」，于是跑去查模型，而模型那次抽得好好的、
     *                 边也真的写进了图里（超时发生在客户端，服务端照写不误）
     * @param skipped  因整批中止而<b>根本没跑</b>的文档数。它和 failed 分开，
     *                 因为「试了没成」和「没试」的处置完全不同
     */
    public record BuildReport(int documents, int accepted, int stored, int rejected,
                              int failed, int skipped, boolean graphAvailable) {
    }

    /**
     * 为全批文档重建图谱。
     * <p>
     * 产品目录<b>在批开始时取一次</b>：抽取按篇进行，逐篇去拉目录等于把同一份几十条的数据
     * 重复拉十几遍；而目录在一次重建期间不会变（它变了就该重跑重建）。
     */
    public BuildReport build(List<Document> documents) {
        String batchId = "graph-" + UUID.randomUUID();
        int total = documents.size();
        List<ProductSummary> catalog = fetchCatalog();
        if (catalog == null) {
            // 目录拿不到就**整批不跑**，不是「这次少抽一类边」。
            // 写入语义是整体替换：商品三元组在半路被丢光之后，那篇文档原有的商品边
            // 会被这次写入删掉，而报告上失败数是 0——看着一切正常，图上少了一整类边。
            // 不跑的话上一版图谱原样留着，代价只是这一批的更新没生效
            log.error("[Graph] 商品目录不可用，本批 {} 篇全部跳过：图谱保持上一版，不写入", total);
            documents.forEach(doc -> recordFailure(batchId, doc.getId(), "CATALOG", "BATCH_SKIPPED",
                    "商品目录不可用，文档未尝试构建", true));
            return new BuildReport(total, 0, 0, 0, 0, total, true);
        }

        int accepted = 0;
        int stored = 0;
        int rejected = 0;
        int failed = 0;
        int skipped = 0;
        int consecutiveFailures = 0;
        boolean available = true;
        for (int i = 0; i < total; i++) {
            Document doc = documents.get(i);
            List<GraphTriplePayload> triples = extract(doc, catalog);
            if (triples == null) {
                recordFailure(batchId, doc.getId(), "EXTRACT", "EXTRACT_FAILED", "模型抽取或解析失败", true);
                // 抽取失败：**不发请求**。写入语义是整体替换，空列表会把这篇文档的旧边清掉。
                // 单篇失败不拖垮整批，但连续失败说明模型侧整体不可用，这时再挨个试下去
                // 只是把同一条错误重复十几行、并且每次都要等满一次模型超时
                failed++;
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    documents.subList(i + 1, total).forEach(skippedDoc -> recordFailure(batchId,
                            skippedDoc.getId(), "BATCH", "BATCH_SKIPPED", "连续失败达到熔断阈值", true));
                    skipped += total - i - 1;
                    log.warn("[Graph] 连续 {} 篇抽取失败，本批中止，其余 {} 篇跳过",
                            consecutiveFailures, total - i - 1);
                    break;
                }
                continue;
            }
            GraphIngestResult result = ingest(doc, triples, batchId);
            if (result == null) {
                failed++;
                recordFailure(batchId, doc.getId(), "WRITE", "WRITE_FAILED", "图谱写入未成功", true);
                // 同一条熔断也覆盖写入侧：Neo4j 可 ping 但写入全失败时，每篇都要等满
                // 驱动的重试窗口（默认 30 秒），十几篇挨个等下去就是十几分钟
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    documents.subList(i + 1, total).forEach(skippedDoc -> recordFailure(batchId,
                            skippedDoc.getId(), "BATCH", "BATCH_SKIPPED", "连续失败达到熔断阈值", true));
                    skipped += total - i - 1;
                    log.warn("[Graph] 连续 {} 篇写入失败，本批中止，其余 {} 篇跳过",
                            consecutiveFailures, total - i - 1);
                    break;
                }
                continue;
            }
            consecutiveFailures = 0;
            accepted += result.getAccepted();
            stored += result.getStored();
            rejected += result.getRejected();
            if (!result.isAvailable()) {
                // 图谱不可用时**立刻停整批**，而不是继续把剩下十几篇挨个试一遍——
                // 那只是把同一条错误重复十几行，还得为每篇白白付一次模型调用
                available = false;
                documents.subList(i + 1, total).forEach(skippedDoc -> recordFailure(batchId,
                        skippedDoc.getId(), "BATCH", "BATCH_SKIPPED", "图谱存储不可用，批次提前结束", true));
                skipped += total - i - 1;
                log.warn("[Graph] 图谱存储不可用，本批在第 {} 篇中止，其余 {} 篇跳过", i + 1, total - i - 1);
                break;
            }
        }

        if (available) {
            runQuietly("孤立实体清理", () -> knowledgeClient.dropGraphOrphans());
        }
        // 报告里给的是 stored 而不是 accepted：演示时读到「入库 40 条」必须真的是
        // 图上有 40 条。图谱不可用那一批两者会差出一整个数量级
        log.info("[Graph] 图谱构建完成：文档 {} 篇，入库 {} 条，丢弃 {} 条，未入库 {} 篇，跳过 {} 篇",
                total, stored, rejected, failed, skipped);
        return new BuildReport(total, accepted, stored, rejected, failed, skipped, available);
    }

    /**
     * 只重建一篇文档的图谱 —— 单篇增量更新的图谱侧入口。
     * <p>
     * <b>为什么单篇可行</b>：写入语义本来就是「按文档整体替换」
     * （{@code GraphService.replaceDocument(docNo, triples)}），换掉一篇不会碰其它篇的边。
     * 全量重建要重抽十几篇、每篇一次模型调用，单篇只需一次。
     * <p>
     * <b>{@code doc == null} 表示「这篇已经不在语料里」</b>：发一条空 triples，
     * 把它的边整体替换成空。没有这一步，停用或删掉的文档会继续在图上给答案——
     * 而且它看起来比检索侧更可信，因为图上那几条边还带着逐字引文。
     * <p>
     * <b>失败时不发空请求</b>：抽取失败（返回 null）与「确实抽到零条」是两件事，
     * 前者若发空列表，会把这篇文档已经建好的边全部清掉，而报告上失败数是 0。
     * <p>
     * <b>目录拿不到就整篇跳过</b>：与全量构建同一条理由——商品键对不上目录时，
     * 那一类边会在这次替换里被删掉，而没有人会知道。
     *
     * @param docNo 文档编号
     * @param doc   该文档的最新内容；为 null 表示它已不在语料中，需要清空其边
     * @return 图谱是否更新成功。false 时图谱保持上一版，调用方不应把它当成致命错误
     */
    public boolean rebuildOne(String docNo, Document doc) {
        String batchId = "graph-one-" + UUID.randomUUID();
        List<ProductSummary> catalog = fetchCatalog();
        if (catalog == null) {
            recordFailure(batchId, docNo, "CATALOG", "CATALOG_UNAVAILABLE", "商品目录不可用，保持旧图谱", true);
            log.error("[Graph] 商品目录不可用，文档 {} 的图谱不更新（保持上一版，不清空）", docNo);
            return false;
        }
        if (doc == null) {
            boolean ok = ingestRaw(docNo, List.of(), batchId);
            if (ok) {
                runQuietly("孤立实体清理", () -> knowledgeClient.dropGraphOrphans());
                log.info("[Graph] 文档 {} 已不在语料中，其图谱边已清空", docNo);
            }
            return ok;
        }
        List<GraphTriplePayload> triples = extract(doc, catalog);
        if (triples == null) {
            recordFailure(batchId, docNo, "EXTRACT", "EXTRACT_FAILED", "模型抽取或解析失败", true);
            log.warn("[Graph] 文档 {} 单篇抽取失败，图谱保持上一版", docNo);
            return false;
        }
        if (!ingestRaw(docNo, triples, batchId)) {
            recordFailure(batchId, docNo, "WRITE", "WRITE_FAILED", "图谱写入未成功", true);
            return false;
        }
        runQuietly("孤立实体清理", () -> knowledgeClient.dropGraphOrphans());
        log.info("[Graph] 文档 {} 单篇图谱已更新（{} 条）", docNo, triples.size());
        return true;
    }

    /**
     * 把抽取结果写进图谱，只回成功与否。
     * <p>
     * 与 {@link #ingest(Document, List)} 的区别：那个要回 {@code GraphIngestResult} 供全量
     * 构建统计 accepted/stored/rejected，单篇更新只关心「这一篇的边换掉了没有」，
     * 多出来的计数没有使用场景。
     */
    private boolean ingestRaw(String docNo, List<GraphTriplePayload> triples, String batchId) {
        try {
            Result<GraphIngestResult> result = knowledgeClient.ingestGraph(GraphIngestPayload.builder()
                    .docNo(docNo)
                    .batchId(batchId)
                    .triples(triples)
                    .build());
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                log.warn("[Graph] 文档 {} 写入失败：{}", docNo,
                        result == null ? "无响应" : result.getMsg());
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("[Graph] 文档 {} 写入异常：{}", docNo, e.getMessage());
            return false;
        }
    }

    /**
     * 抽一篇文档的关系。<b>失败返回 {@code null}</b>，与「抽到了零条」区分开——
     * 前者不能写入，后者可以（这篇确实没有可用关系）。
     */
    private List<GraphTriplePayload> extract(Document doc, List<ProductSummary> catalog) {
        String content = doc.getContent();
        if (content == null || content.isBlank()) {
            return List.of();
        }
        if (content.length() > MAX_DOC_CHARS) {
            log.warn("[Graph] 文档 {} 正文 {} 字，超过 {} 上限，本次只抽前一段",
                    doc.getId(), content.length(), MAX_DOC_CHARS);
        }

        try {
            // 抽取要确定性输出：温度 0，且不继承对话侧的任何采样参数
            LLMConfig config = LLMConfig.builder()
                    .model(defaultConfig.getModel())
                    .temperature(0.0)
                    .maxTokens(EXTRACT_MAX_TOKENS)
                    .build();
            LLMResponse response = llmProvider.chat(List.of(
                    ChatMessage.builder().role(ChatMessage.Role.SYSTEM)
                            .content(instruction(catalog)).build(),
                    ChatMessage.builder().role(ChatMessage.Role.USER)
                            .content("文档编号：" + doc.getId()
                                    + "\n文档标题：" + doc.getTitle()
                                    + "\n\n正文：\n" + truncate(content)).build()), config);
            List<GraphTriplePayload> parsed = parse(response.getContent());
            if (parsed == null) {
                // 解析失败 = 这一篇没抽成，**不能写入**（见 parse 的 javadoc）
                return null;
            }
            List<GraphTriplePayload> linked = linkProducts(parsed, catalog);
            linked = restrictProductsToDeclaredSubjects(linked, doc);
            // **确定性地补出「商品→成分」这条边。**
            // 它原先是纯靠模型抽的，实测会漏抽（37 个商品里 13 个因此没有节点）。
            // 而这件事根本不需要模型判断：文档讲的是哪个商品在上传时已被人工声明，
            // 随语料下发到这边（{@code doc.getSubjectSpuIds()}）。
            // 模型只负责抽成分，商品端由这里补齐——两者合起来才是完整的 CONTAINS。
            List<GraphTriplePayload> augmented = augmentSubjectEdges(doc, linked, catalog);
            log.info("[Graph] 文档 {} 抽取 {} 条，商品链接后 {} 条，补主体边后 {} 条",
                    doc.getId(), parsed.size(), linked.size(), augmented.size());
            return augmented;
        } catch (Exception e) {
            // 单篇失败不拖垮整批：一篇文档抽不出来，不影响其余文档的图谱
            log.warn("[Graph] 文档 {} 抽取失败，本次跳过（保留其已有边）：{}", doc.getId(), e.getMessage());
            return null;
        }
    }

    private GraphIngestResult ingest(Document doc, List<GraphTriplePayload> triples, String batchId) {
        try {
            Result<GraphIngestResult> result = knowledgeClient.ingestGraph(GraphIngestPayload.builder()
                    .docNo(doc.getId())
                    .batchId(batchId)
                    .triples(triples)
                    .build());
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                log.warn("[Graph] 文档 {} 写入失败：{}", doc.getId(),
                        result == null ? "无响应" : result.getMsg());
                return null;
            }
            return result.getData();
        } catch (RuntimeException e) {
            log.warn("[Graph] 文档 {} 写入异常：{}", doc.getId(), e.getMessage());
            return null;
        }
    }

    private void recordFailure(String batchId, String docNo, String stage, String reason, String detail, boolean retryable) {
        try {
            knowledgeClient.recordGraphFailure(new GraphBuildFailurePayload(batchId, docNo, null, stage, reason, detail, retryable));
        } catch (RuntimeException e) {
            log.warn("[Graph] 失败记录写入失败 docNo={} stage={}: {}", docNo, stage, e.getMessage());
        }
    }

    // ==================== 商品实体链接 ====================

    /**
     * 把模型写出的商品实体对齐到真实 SPU 上。
     * <p>
     * 两种写法都接受：直接给键（{@code SPU7}），或给商品名（{@code 鱼油软胶囊}）——
     * 模型两种都可能吐，而它们指向同一个商品。对齐之后<b>标签一律用目录里的名字覆盖</b>，
     * 模型写错一个空格也不影响显示。
     * <p>
     * 对不上的整条丢弃。理由不是洁癖：一个拼错的商品节点在图上是个<b>真实存在但永远连不上
     * 任何东西的孤岛</b>，查询时表现为「这个商品没有已知相互作用」——一个不报错的错误答案。
     */
    private List<GraphTriplePayload> linkProducts(List<GraphTriplePayload> triples,
                                                  List<ProductSummary> catalog) {
        Map<String, ProductSummary> byKey = new LinkedHashMap<>();
        Map<String, ProductSummary> byName = new LinkedHashMap<>();
        for (ProductSummary p : catalog) {
            byKey.put(key(p.getId()), p);
            byName.put(normalize(p.getName()), p);
        }

        List<GraphTriplePayload> out = new ArrayList<>(triples.size());
        for (GraphTriplePayload t : triples) {
            String head = resolve(t.getHeadKind(), t.getHeadName(), byKey, byName);
            String tail = resolve(t.getTailKind(), t.getTailName(), byKey, byName);
            if (head == null || tail == null) {
                log.debug("[Graph] 丢弃：商品无法对齐到目录 {} -> {}", t.getHeadName(), t.getTailName());
                continue;
            }
            t.setHeadName(head);
            t.setTailName(tail);
            // 商品名统一由目录提供，覆盖模型写的那份
            ProductSummary hp = byKey.get(head);
            if (hp != null) {
                t.setHeadLabel(hp.getName());
            }
            ProductSummary tp = byKey.get(tail);
            if (tp != null) {
                t.setTailLabel(tp.getName());
            }
            out.add(t);
        }
        return out;
    }

    /**
     * 说明书的人工主体声明是商品归属的硬边界。
     * 模型可能在正文中提到其他商品或把「本品」误识别成相似商品；这些实体不能成为
     * 当前说明书的商品主体，否则会把一篇孕期 DHA 说明书污染到鱼油商品上。
     */
    private List<GraphTriplePayload> restrictProductsToDeclaredSubjects(
            List<GraphTriplePayload> triples, Document doc) {
        if (doc.getSubjectSpuIds() == null || doc.getSubjectSpuIds().isEmpty()) {
            return triples;
        }
        Set<String> allowed = doc.getSubjectSpuIds().stream()
                .filter(java.util.Objects::nonNull)
                .map(KnowledgeGraphBuilder::key)
                .collect(java.util.stream.Collectors.toSet());
        return triples.stream().filter(triple -> {
            if (!EntityKind.PRODUCT.name().equals(triple.getHeadKind())) {
                return true;
            }
            boolean keep = allowed.contains(triple.getHeadName());
            if (!keep) {
                log.warn("[Graph] 文档 {} 丢弃未声明的商品主体 {}，允许主体={}",
                        doc.getId(), triple.getHeadName(), allowed);
            }
            return keep;
        }).toList();
    }

    /**
     * 按**人工声明的归属**确定性补出 {@code SPUx -CONTAINS-> 成分} 边。
     * <p>
     * <b>为什么必须由代码补，而不是指望模型抽</b>：这条边是「商品有没有资料」的唯一判据
     * （见 {@code GraphService.documentsOfProduct} / {@code coverage}），而它原先完全依赖
     * 模型从「本品含 X」这句话里抽出商品端。实测这条边会被漏抽——37 个商品里 13 个因此
     * 在图上没有节点，覆盖率永远到不了 100%，而缺失没有任何现象（文档在库里、也进了向量库）。
     * <p>
     * <b>补边的输入来自两处</b>：归属由运营在上传时声明（{@code subjectSpuIds}），
     * 候选实体来自抽取结果，组成事实必须另有正文的肯定声明。
     * 相互作用中提到某营养素只证明它参与了相互作用，不能证明商品含有它。
     * <p>
     * <b>只在文档确实抽出成分时才补</b>：一篇抽不出任何成分的文档，硬造一条
     * {@code SPUx -CONTAINS-> ???} 只会往图上灌一个没有依据的节点。这种情况下
     * 商品在覆盖率里显示为「有节点、无边」——那正是需要运营去补正文的信号，
     * 不该被一条编出来的边掩盖。
     * <p>
     * 多商品说明书必须保留各商品自己的 CONTAINS，不按所有主体与所有成分做笛卡尔积。
     * PROVIDES 保留为成分到营养素的原关系，不把提供对象直接补成商品组成。
     */
    private List<GraphTriplePayload> augmentSubjectEdges(Document doc, List<GraphTriplePayload> triples,
                                                         List<ProductSummary> catalog) {
        List<Long> subjects = doc.getSubjectSpuIds();
        if (subjects == null || subjects.isEmpty()) {
            // 领域文档（退货政策、监管规范）本就不属于任何商品，不需要补
            return triples;
        }
        Map<String, ProductSummary> byKey = new LinkedHashMap<>();
        for (ProductSummary p : catalog) {
            byKey.put(key(p.getId()), p);
        }
        // 声明的商品不在目录里（下架、或编号错）：补出来的节点永远连不上目录，
        // 查询时表现为「这个商品没有已知相互作用」—— 一个不报错的错误答案
        for (Long spuId : subjects) {
            if (!byKey.containsKey(key(spuId))) {
                log.warn("[Graph] 文档 {} 声明的商品 {} 不在目录中，跳过主体边", doc.getId(), key(spuId));
            }
        }
        // 归属解析分两条路，判据是「这份文档的组成主语是什么」。
        // 单主体文档的主语是「本品」；多主体文档里「本品」指的是**几款商品一起**，
        // 拿它当主语会把各商品的成分合成同一份配方——那边只能由正文里的商品名锚点确定归属。
        List<SubjectComposition> declared = subjects.size() == 1
                ? singleSubjectCompositions(doc, triples, key(subjects.get(0)))
                : multiSubjectCompositions(doc, subjects, byKey, triples);
        if (declared.isEmpty()) {
            log.debug("[Graph] 文档 {} 声明了 {} 个主体商品，但没解析出可归属的组成，不补主体边",
                    doc.getId(), subjects.size());
            return triples;
        }
        // 已经由模型抽出的商品→成分对，避免重复。
        // 键取**规范名**：别名表会把「叶黄素酯」并到「叶黄素」，拿原始写法去重的话，
        // 同一篇文档里补出的边会与模型抽出的边重复一遍
        Set<String> existing = new HashSet<>();
        for (GraphTriplePayload t : triples) {
            if (GraphRelation.CONTAINS.name().equals(t.getRelation())) {
                existing.add(t.getHeadName() + "\u0000" + ingredientKey(t.getTailName()));
            }
        }
        List<GraphTriplePayload> out = new ArrayList<>(triples);
        int added = 0;
        for (SubjectComposition composition : declared) {
            ProductSummary product = byKey.get(composition.spuKey());
            if (product == null
                    || !existing.add(composition.spuKey() + "\u0000" + ingredientKey(composition.ingredient()))) {
                continue;
            }
            out.add(GraphTriplePayload.builder()
                    .headKind(EntityKind.PRODUCT.name())
                    .headName(composition.spuKey())
                    .headLabel(product.getName())
                    .relation(GraphRelation.CONTAINS.name())
                    .tailKind(EntityKind.INGREDIENT.name())
                    .tailName(composition.ingredient())
                    .tailLabel(composition.label())
                    .quote(composition.quote())
                    // 商品端由人工主体绑定证明，组成端保留真实声明原句。
                    .declared(true)
                    .build());
            added++;
        }
        if (added > 0) {
            log.info("[Graph] 文档 {} 按声明的归属补出 {} 条商品→成分边", doc.getId(), added);
        }
        return out;
    }

    /**
     * 一条「某商品含有某成分」的声明。
     *
     * @param spuKey     商品键（形如 {@code SPU16}）
     * @param ingredient 成分的<b>原文写法</b>。不能存规范名：服务端校验要拿它去正文里找，
     *                   而正文写的是「富马酸亚铁」这类变体，换成规范名「铁剂」就找不到了
     * @param label      展示名
     * @param quote      声明原句，用于溯源与切片定位
     */
    private record SubjectComposition(String spuKey, String ingredient, String label, String quote) {
    }

    /** 抽取结果里的成分候选：规范名 → 模型写的原文与展示名 */
    private record IngredientCandidate(String name, String label) {
    }

    /**
     * 单主体文档的组成声明：主语是「本品」，候选成分来自抽取结果。
     * <p>
     * 判据与原先一致（{@link #compositionQuote}）——「本品含 X」这类句子里，
     * 商品端只有一个可能，缺的只是把成分认全。
     */
    private static List<SubjectComposition> singleSubjectCompositions(Document doc, List<GraphTriplePayload> triples,
                                                                     String spuKey) {
        Map<String, SubjectComposition> out = new LinkedHashMap<>();
        for (GraphTriplePayload t : triples) {
            collectComposition(out, doc.getContent(), spuKey, t.getHeadKind(), t.getHeadName(), t.getHeadLabel());
            collectComposition(out, doc.getContent(), spuKey, t.getTailKind(), t.getTailName(), t.getTailLabel());
        }
        return List.copyOf(out.values());
    }

    private static void collectComposition(Map<String, SubjectComposition> out, String content, String spuKey,
                                           String kind, String name, String label) {
        if (!isCompositionPart(kind) || name == null || name.isBlank()) {
            return;
        }
        String quote = compositionQuote(content, name);
        if (quote == null) {
            return;
        }
        out.putIfAbsent(ingredientKey(name),
                new SubjectComposition(spuKey, name, labelOrName(label, name), quote));
    }

    /**
     * 多主体文档的组成声明：主语是正文里的<b>商品名锚点</b>，不是「本品」。
     * <p>
     * <b>为什么不能沿用单主体那条路</b>：多主体说明书通篇写「本品」，而「本品」指的是
     * <b>几款商品一起</b>——按单主体的判据补边，等于把各商品各自的成分合成同一份配方，
     * 而这是比「没补上」更糟的错误（它看起来有出处）。这条边只能按商品名逐段归属。
     * <p>
     * <b>为什么不能整篇按「所有主体 × 所有成分」补</b>：那是笛卡尔积。KB-0018 的
     * 玉米黄质只在 spu18 名下，spu19 拿不到它——补错了同样是「看起来有出处」。
     * <p>
     * <b>逐段归属怎么做</b>：按句号/分号切「声明单元」，单元内按逗号切子句；
     * 子句各自认锚点（商品名子串），认不出的按「先前、后后」接上相邻锚点——
     * 「叶黄素酯软胶囊，含叶黄素酯与玉米黄质」成分列表在后一个子句，
     * 而「含锌与硒的锌硒宝片」把商品名放在后面，两个方向都得接得上。
     * <b>只有带组成关键词的子句才贡献成分</b>：正文里单独提到某个成分名
     * （「褪黑素是松果体分泌的一种激素」）不是组成声明，不该据此补边。
     */
    private static List<SubjectComposition> multiSubjectCompositions(Document doc, List<Long> subjects,
                                                                    Map<String, ProductSummary> byKey,
                                                                    List<GraphTriplePayload> triples) {
        String content = doc.getContent();
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Map<String, IngredientCandidate> candidates = compositionCandidates(triples);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<SubjectComposition> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String unit : content.split("[。！？；;\n]")) {
            if (unit.isBlank() || isNegatedComposition(unit)) {
                continue;
            }
            String[] clauses = unit.split("[，,：:]");
            String[] own = new String[clauses.length];
            for (int i = 0; i < clauses.length; i++) {
                List<String> hits = anchorKeysOf(clauses[i], subjects, byKey, candidates);
                own[i] = hits.size() == 1 ? hits.get(0) : null;
            }
            for (int i = 0; i < clauses.length; i++) {
                if (!isCompositionClause(clauses[i])) {
                    continue;
                }
                String spuKey = own[i] != null ? own[i] : nearestAnchor(own, i);
                if (spuKey == null) {
                    continue;
                }
                String quote = clauses[i].strip();
                if (EntityNames.stripBlanks(quote).length() < LOCATABLE_QUOTE_CHARS) {
                    // 太短的子句在正文里定位不到（服务端要求引文至少 6 个字），
                    // 退回整个声明单元——它同样是原文里的连续片段
                    quote = unit.strip();
                }
                for (Map.Entry<String, IngredientCandidate> e : candidates.entrySet()) {
                    if (!mentionsIngredient(clauses[i], e.getValue().name())) {
                        continue;
                    }
                    if (!seen.add(spuKey + "\u0000" + e.getKey())) {
                        continue;
                    }
                    out.add(new SubjectComposition(spuKey, e.getValue().name(),
                            labelOrName(e.getValue().label(), e.getValue().name()), quote));
                }
            }
        }
        return out;
    }

    /** 抽取结果里出现过的成分 —— 组成事实必须另有正文的肯定声明，候选只回答「这篇提到了什么」 */
    private static Map<String, IngredientCandidate> compositionCandidates(List<GraphTriplePayload> triples) {
        Map<String, IngredientCandidate> out = new LinkedHashMap<>();
        for (GraphTriplePayload t : triples) {
            addCandidate(out, t.getHeadKind(), t.getHeadName(), t.getHeadLabel());
            addCandidate(out, t.getTailKind(), t.getTailName(), t.getTailLabel());
        }
        return out;
    }

    private static void addCandidate(Map<String, IngredientCandidate> out, String kind, String name, String label) {
        if (!isCompositionPart(kind) || name == null || name.isBlank()) {
            return;
        }
        out.putIfAbsent(ingredientKey(name), new IngredientCandidate(name, labelOrName(label, name)));
    }

    /**
     * 子句命中了哪些声明的商品主体 —— 按「商品名的 {@value #ANCHOR_MIN_CHARS} 字以上子串
     * 出现在子句里」判定。
     * <p>
     * <b>用子串而不是整名相等</b>：多主体说明书的正文里商品名常常只写一个片段——
     * KB-0017 写「缓释片」「软糖」，而目录里的名字是「褪黑素缓释片」「γ-氨基丁酸软糖」。
     * 要求整名命中等于永远匹配不上。
     * <p>
     * <b>唯一性只在声明的几个主体之间判</b>：「软糖」在整个目录里还命中另外两个商品，
     * 但那两个不是这篇文档的主体，不该参与竞争——参与的话「软糖」永远是歧义锚点，
     * spu17 也就永远补不上。
     */
    private static List<String> anchorKeysOf(String clause, List<Long> subjects, Map<String, ProductSummary> byKey,
                                            Map<String, IngredientCandidate> candidates) {
        String text = EntityNames.normalize(clause);
        List<int[]> covered = ingredientSpans(text, candidates);
        String best = null;
        int bestLength = 0;
        boolean tied = false;
        for (Long spuId : subjects) {
            ProductSummary product = byKey.get(key(spuId));
            if (product == null || product.getName() == null) {
                continue;
            }
            int length = longestFragment(EntityNames.normalize(product.getName()), text, covered);
            if (length < ANCHOR_MIN_CHARS) {
                continue;
            }
            if (length > bestLength) {
                best = key(spuId);
                bestLength = length;
                tied = false;
            } else if (length == bestLength) {
                tied = true;
            }
        }
        return best == null || tied ? List.of() : List.of(best);
    }

    /**
     * 子句里被<b>候选成分</b>占用的字符区间。
     * <p>
     * <b>为什么商品名片段落在其中就不算锚点</b>：实测 KB-0018 有一句
     * 「越橘提取物的主要成分为花青素类物质」——它讲的是<b>成分</b>由什么构成，
     * 不是商品由什么构成。但商品名「越橘叶黄素片」的前两个字「越橘」正好落在这句话里，
     * 于是这条边被补成了 {@code spu19 -CONTAINS-> 花青素类物质}：一个<b>看起来有出处</b>、
     * 实际张冠李戴的结论。更长的那个提及（「越橘提取物」）本来就该赢——
     * 谁的名字更长、更具体，这段文字就是谁的。
     */
    private static List<int[]> ingredientSpans(String text, Map<String, IngredientCandidate> candidates) {
        List<int[]> spans = new ArrayList<>();
        for (IngredientCandidate candidate : candidates.values()) {
            for (String variant : ingredientVariants(candidate.name())) {
                if (variant.isEmpty()) {
                    continue;
                }
                for (int at = text.indexOf(variant); at >= 0; at = text.indexOf(variant, at + 1)) {
                    spans.add(new int[]{at, at + variant.length()});
                }
            }
        }
        return spans;
    }

    private static boolean coveredBy(List<int[]> spans, int start, int end) {
        for (int[] span : spans) {
            if (start >= span[0] && end <= span[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 名字与文本的最长公共子串长度。
     * <p>
     * <b>为什么取最长而不是「命中即算」</b>：同系列的商品名天然共享片段——
     * 「叶黄素酯软胶囊」与「越橘叶黄素片」共享「叶黄素」。命中即算的话两个锚点永远同时命中，
     * 判定退化成「歧义」，整篇文档一条边都补不出来（这正是修复前的实测结果）。
     * 取最长则前者以 7 字、后者以 6 字各自胜出，各归各位；只有最长也并列时才是真歧义。
     */
    private static int longestFragment(String name, String text, List<int[]> covered) {
        for (int length = name.length(); length >= ANCHOR_MIN_CHARS; length--) {
            for (int start = 0; start + length <= name.length(); start++) {
                int at = text.indexOf(name.substring(start, start + length));
                if (at >= 0 && !coveredBy(covered, at, at + length)) {
                    return length;
                }
            }
        }
        return 0;
    }

    /** 认不出锚点的子句接相邻锚点：先前、后后。跨商品的归属不在这里判，见声明单元切分 */
    private static String nearestAnchor(String[] own, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (own[i] != null) {
                return own[i];
            }
        }
        for (int i = index + 1; i < own.length; i++) {
            if (own[i] != null) {
                return own[i];
            }
        }
        return null;
    }

    /** 子句是不是组成声明 —— 只有带关键词的子句才贡献成分，见 {@link #multiSubjectCompositions} */
    private static boolean isCompositionClause(String clause) {
        return COMPOSITION_KEYWORD.matcher(clause).find();
    }

    /** 否定组成：「本品不含乳糖」里的乳糖不是组成。整单元跳过，不做半句裁剪 */
    private static boolean isNegatedComposition(String unit) {
        return unit.contains("不含") || unit.contains("而非") || unit.contains("没有含");
    }

    /**
     * 子句里有没有提到这个候选成分 —— 按规范名与它的全部别名变体做包含判断。
     * <p>
     * 走别名变体而不是只比名字：正文写「抗坏血酸（维生素 C）」而模型抽出的名字可能是
     * 「维生素C」，只比一个写法就会漏掉这次声明。
     */
    private static boolean mentionsIngredient(String clause, String candidate) {
        String text = EntityNames.normalize(clause);
        for (String variant : ingredientVariants(candidate)) {
            if (!variant.isEmpty() && text.contains(variant)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一个候选成分的全部写法（已规范化）：规范名、别名表里的变体、以及模型写的那份名字。
     * <p>
     * 走别名变体而不是只比一个写法：正文写「抗坏血酸（维生素 C）」而模型抽出的名字可能是
     * 「维生素C」，只比一个写法就会漏掉这次声明。
     */
    private static List<String> ingredientVariants(String candidate) {
        String normalized = EntityNames.normalize(candidate);
        if (normalized.isEmpty()) {
            return List.of();
        }
        EntityAliases.Canonical canonical = EntityAliases.resolve(normalized);
        List<String> variants = canonical == null ? List.of(normalized)
                : EntityAliases.variantsOf(canonical.name());
        if (variants.contains(normalized)) {
            return variants;
        }
        List<String> all = new ArrayList<>(variants);
        all.add(normalized);
        return all;
    }

    /** 成分去重键：别名表归并后的规范名，与图谱上的节点键同一套规则 */
    private static String ingredientKey(String name) {
        String normalized = normalize(name);
        EntityAliases.Canonical canonical = EntityAliases.resolve(normalized);
        return canonical == null ? normalized : canonical.name();
    }

    private static String labelOrName(String label, String name) {
        return label == null || label.isBlank() ? name : label;
    }

    /** 候选物质必须位于肯定组成声明的列举部分，后面的共服条件不是组成。 */
    private static String compositionQuote(String content, String name) {
        if (content == null || name == null || name.isBlank()) {
            return null;
        }
        EntityAliases.Canonical canonical = EntityAliases.resolve(normalize(name));
        List<String> variants = canonical == null ? List.of(normalize(name))
                : EntityAliases.variantsOf(canonical.name());
        for (String sentence : content.split("[。；;\\r\\n]")) {
            boolean productSubject = false;
            String[] clauses = normalize(sentence).split("[，,]");
            for (int index = 0; index < clauses.length; index++) {
                String clause = clauses[index].replaceFirst("^\\d+(?:\\.\\d+)*[、.]?", "");
                var declaration = COMPOSITION_DECLARATION.matcher(clause);
                boolean explicitProduct = declaration.find();
                if (!explicitProduct) {
                    declaration = UNIT_COMPOSITION.matcher(clause);
                    if (!declaration.find() || (index > 0 && !productSubject
                            && !clause.startsWith("本品") && !clause.startsWith("本产品"))) {
                        declaration = COMPOSITION_FIELD.matcher(clause);
                        if (!declaration.find() || (index > 0 && !productSubject)) {
                            declaration = COMPOSITION_CONTINUATION.matcher(clause);
                            if (!productSubject || !declaration.find()) {
                                // 本品的身份介绍可延续到下一组成子句；共服或其他对象会截断主语。
                                productSubject = clause.matches("^(?:本品|本产品)为[^与]*$")
                                        || (productSubject && clause.startsWith("商品名为"));
                                continue;
                            }
                        }
                    }
                }
                String listed = clause.substring(declaration.end());
                // “含铁制剂应与本品间隔”中的主语是含铁制剂，不能继承本品。
                if (!explicitProduct && listed.matches(
                        ".*(?:制剂|药物|药品).*(?:同服|合用|间隔|应|需|建议|不宜|禁用|不得|不能|服用|影响|吸收|竞争|风险).*")) {
                    productSubject = false;
                    continue;
                }
                productSubject = true;
                // “本品所含钙与铁剂同服…”只声明了钙，不能把铁剂纳入组成。
                if (listed.matches(".*(?:同服|合用|相互|竞争|影响|吸收).*")) {
                    listed = listed.split("与|和", 2)[0];
                }
                listed = listed.split("(?:不含|而非|以促进|以改善|可|会|应|需|建议)", 2)[0];
                String composition = listed;
                if (variants.stream().map(EntityNames::normalize).anyMatch(composition::contains)) {
                    return sentence.strip();
                }
            }
        }
        return null;
    }

    /**
     * 是不是组合三元组。
     * <p>
     * 判据是<b>关系名</b>而不是头类型：{@code COMBINATION} 这个类型名不该出现在
     * 抽取提示词的类型清单里（词表由 {@code EntityKind.values()} 生成，
     * 组合是结构不是知识，列进去会让模型到处去建组合节点）。所以这里靠关系名分流，
     * 与 {@code GraphRelation.COMBINED_WITH} 是同一份判据。
     */
    /**
     * 一个类型名是不是「商品的组成」。
     * <p>
     * 同时收 INGREDIENT 与 NUTRIENT：模型对「锌」这类东西的标注在两者间摇摆，
     * 而 EntityAliases 会把它统一成 NUTRIENT。判据与 {@code GraphRelation.CONTAINS}
     * 的尾端词表保持一致——两处不一致时，这里补出的边会被那边丢掉，
     * 表现为「补了边但商品还是没节点」。
     */
    private static boolean isCompositionPart(String kind) {
        EntityKind k = EntityKind.parse(kind);
        return k == EntityKind.INGREDIENT || k == EntityKind.NUTRIENT;
    }

    private static boolean isCombination(GraphTriplePayload t) {
        return GraphRelation.COMBINED_WITH == GraphRelation.parse(t.getRelation());
    }

    /** 商品实体解析成 SPU 键；非商品类型原样返回；商品但解析不出来返回 {@code null} */
    private static String resolve(String kind, String name, Map<String, ProductSummary> byKey,
                                  Map<String, ProductSummary> byName) {
        if (EntityKind.parse(kind) != EntityKind.PRODUCT) {
            return name == null ? "" : name.strip();
        }
        String raw = name == null ? "" : name.strip();
        if (raw.isEmpty()) {
            return null;
        }
        if (byKey.containsKey(raw) || byKey.containsKey(raw.toUpperCase())) {
            return byKey.containsKey(raw) ? raw : raw.toUpperCase();
        }
        ProductSummary byProductName = byName.get(normalize(raw));
        return byProductName == null ? null : key(byProductName.getId());
    }

    /**
     * 商品的图谱节点键。
     * <p>
     * <b>这是跨服务的约定，改这里必须改 knowledge-service 那边的理解</b>——
     * 目前只有这一处生产、以及 {@code InteractionTool} 一处消费，两边都调用本方法。
     * 用 SPU 编号而不是商品名：商品会改名、会重名，而编号不会。
     */
    public static String key(Long spuId) {
        return "SPU" + spuId;
    }

    /**
     * 与 knowledge-service 的实体名规范化保持一致。
     * <p>
     * 委托给 {@link EntityNames} 而不是在这里再写一遍：这两处算的是同一个<b>节点键</b>，
     * 各写一遍就意味着「改了一边忘了另一边」，而症状是同名实体在图上分裂成两个节点。
     */
    private static String normalize(String s) {
        return EntityNames.normalize(s);
    }

    // ==================== 提示词 ====================

    /**
     * 抽取提示词。
     * <p>
     * 词表与关系名<b>从枚举里生成</b>，不手写字符串：这是两个服务共用的同一份定义，
     * 手抄一份到提示词里就会出现「改了枚举、忘了改提示词」，而症状是模型一直返回一个
     * 已被删掉的关系名、整批被静默丢弃。
     */
    private String instruction(List<ProductSummary> catalog) {
        String kinds = java.util.Arrays.stream(EntityKind.values())
                .map(k -> k.name() + "=" + k.label())
                .collect(Collectors.joining("、"));
        // 每条关系带上「哪一端能是什么」是由枚举自己生成的，提示词与校验器因此不可能漂移
        String relations = java.util.Arrays.stream(GraphRelation.values())
                .map(r -> r.name() + "：" + r.signature())
                .collect(Collectors.joining("\n"));

        return """
                你是产品文档的知识抽取器。从给定文档中抽取实体之间的<b>关系</b>，只输出 JSON。

                ## 可用实体类型
                %s

                ## 可用关系（只能从这里选）
                %s

                ## 本平台在售商品目录
                商品一律用**编号**作为 headName/tailName（如 SPU7），显示名由系统按目录补齐。
                只有文档明确描述的是目录中的某个商品时，才把它抽成 PRODUCT；
                对不上就整条不要。

                %s

                ## 硬性规则
                1. **只抽文档里明确写出来的关系。** 不要用你自己的医学常识补充任何一条。
                   文档没写「A 和 B 有相互作用」，就不要输出这条关系——哪怕这是常识。
                2. **quote 必须是文档正文里的原句片段，逐字复制。**
                   可以截取其中一段，但不得改写、不得拼接不相邻的句子、不得自己组织语言。
                   系统会拿它去原文里比对，对不上的整条会被丢弃。
                3. **商品只通过 CONTAINS 指向成分，不直接连到药物或人群。**
                   「本品与华法林合用可能增加出血风险」要拆成两条：
                   `SPU7 -CONTAINS-> 鱼油`，`鱼油 -INTERACTS_WITH-> 华法林`。
                   与药物、人群发生关系的主体永远是**成分或营养素**，不是商品——
                   同一个成分可能来自好几个商品，挂在商品上这条推理就只对那一个商品成立。
                4. **关系两端的类型必须符合上面写出的形状**，不符合的整条会被丢弃。
                   文档里常常只说了「本品含 X」而没说 X 是什么类型，按常识判断即可，
                   但关系与引文仍必须来自文档。
                5. **不要输出「风险」「不良反应」这类实体。** 后果写在关系的 effect 字段里，
                   不单独建节点——风险是组合导致的，单独一条「维生素D → 高钙血症」是错的。
                5b. **COMBINED_WITH 表示原文明示的组合注意事项，不表示独特三阶风险。**
                   文档写「铁剂与钙剂同服影响吸收」时，那只涉及两样，用
                   「铁剂 -INTERACTS_WITH-> 钙剂」而不是组合。原句明确列出三样及以上
                   共同服用的物质并说明共同后果时，可以抽成组合；不要推断两两安全。
                   headKind 填 COMBINATION，headName 用「+」连接全部成员
                   （如 钙剂+铁剂+锌），relation 填 COMBINED_WITH。
                   tailName 填原句明确提到的受影响成分、营养素、药物、药物类别或人群，
                   可以是组合中的成员。effect 必须从 quote 逐字截取，不得改写或推断。
                   **成员、tailName 和 effect 必须同时出现在同一段 quote 里**。
                   原文「钙剂、铁剂、锌三者同时补充时，在肠道吸收阶段会发生竞争并彼此抑制」
                   可用锌为 tailName、NUTRIENT 为 tailKind，effect 原样填
                   「在肠道吸收阶段会发生竞争并彼此抑制」。不要由该句生成结石或疾病结论。
                   如果文档只说了「与他药同服需注意」这种没有点名具体药物的，不要抽。
                6. 文档里的「本品」「本产品」指的就是这篇文档所描述的那个商品。
                7. 抽取不到任何关系时输出 {"triples":[]}。

                ## 输出格式（只输出 JSON，不要解释，不要 markdown 代码块）
                {"triples":[{"headKind":"","headName":"","relation":"","tailKind":"","tailName":"","effect":"","quote":""}]}
                """.formatted(kinds, relations, catalogText(catalog));
    }

    private static String catalogText(List<ProductSummary> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            // 目录拉不到时不静默降级成「没有商品」：那会让模型在文档里看到商品名时无从对应，
            // 于是干脆不抽商品相关的关系，而这一批的图上就少了一整类边
            return "（目录暂时不可用，本次不要抽取任何 PRODUCT 类型的关系）";
        }
        return catalog.stream()
                .map(p -> "- " + key(p.getId()) + " = " + p.getName()
                        + (p.getSubtitle() == null || p.getSubtitle().isBlank() ? "" : "（" + p.getSubtitle() + "）"))
                .collect(Collectors.joining("\n"));
    }

    private static String truncate(String content) {
        return content.length() <= MAX_DOC_CHARS ? content : content.substring(0, MAX_DOC_CHARS);
    }

    // ==================== 解析 ====================

    /**
     * 解析模型返回的 JSON。
     * <p>
     * 容忍 markdown 代码块与前后废话：模型很爱回 {@code ```json ... ```}，
     * 而这段围栏会让反序列化直接失败——一次能把整篇文档的关系全丢掉的失败，
     * 起因只是多了三个反引号。所以先截出最外层的花括号再解析。
     * <p>
     * <b>解析不出来返回 {@code null}，不是空列表</b>——这一条是本方法唯一难懂的地方，
     * 也是踩过的坑。返回值往上传给 {@link #extract}，再往上决定要不要发写入请求；
     * 而写入语义是「整体替换」。第一版把解析失败也返回 {@code List.of()}，
     * 于是模型输出被 {@code max_tokens} 截断一次，这篇文档图上已经建好的边
     * 就在一次重建里<b>全部消失</b>，而报告上失败数是 0、日志只说「抽取 0 条」。
     * 窗口期内用户问「这两个能不能一起吃」得到的是「未收录」——安全场景里的假阴性。
     * <p>
     * 包级可见是为了让测试能直接钉住这条边界：截断的 JSON 必须返回 {@code null}，
     * {@code {"triples":[]}} 才能返回空列表。
     */
    static List<GraphTriplePayload> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("[Graph] 抽取结果为空");
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("[Graph] 抽取结果里找不到 JSON 对象：{}", abbreviate(raw));
            return null;
        }
        try {
            Map<String, List<GraphTriplePayload>> parsed = MAPPER.readValue(
                    raw.substring(start, end + 1), new TypeReference<>() {
                    });
            List<GraphTriplePayload> triples = parsed.get("triples");
            if (triples == null) {
                // 解析成功但没有 triples 这个键，说明模型换了输出格式，与「这篇文档
                // 确实没有可抽的关系」不是一回事——后者模型会回 {"triples":[]}
                log.warn("[Graph] 抽取结果里没有 triples 字段：{}", abbreviate(raw));
                return null;
            }
            return triples.stream()
                    .filter(t -> t != null && t.getRelation() != null)
                    .limit(MAX_TRIPLES)
                    .toList();
        } catch (Exception e) {
            log.warn("[Graph] 抽取结果无法解析：{} | {}", e.getMessage(), abbreviate(raw));
            return null;
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    // ==================== 辅助 ====================

    /**
     * 拉商品目录。<b>失败返回 {@code null}</b>，与「目录确实没有商品」区分开。
     * <p>
     * 这个区分是必须的：拿到空目录时 {@link #linkProducts} 会把所有 PRODUCT 三元组丢光，
     * 然后那份「少了商品边」的列表照发不误，而服务端是整体替换——
     * 结果是<b>把这篇文档原有的商品边删掉</b>，报告上失败数还是 0。
     * 失败（null）则让调用方整批不跑，上一版图谱原样留着。
     */
    private List<ProductSummary> fetchCatalog() {
        try {
            Result<List<ProductSummary>> result = productClient.catalog();
            if (result == null || result.getCode() == null || result.getCode() != 200 || result.getData() == null) {
                log.warn("[Graph] 商品目录拉取失败：{}", result == null ? "无响应" : result.getMsg());
                return null;
            }
            log.info("[Graph] 商品目录 {} 条，用于实体链接", result.getData().size());
            return result.getData();
        } catch (RuntimeException e) {
            log.warn("[Graph] 商品目录拉取异常：{}", e.getMessage());
            return null;
        }
    }

    private void runQuietly(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("[Graph] {} 失败，不影响本次构建：{}", what, e.getMessage());
        }
    }
}
