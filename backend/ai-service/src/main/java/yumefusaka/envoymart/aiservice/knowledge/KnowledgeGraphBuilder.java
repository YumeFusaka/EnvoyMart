package yumefusaka.envoymart.aiservice.knowledge;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        int total = documents.size();
        List<ProductSummary> catalog = fetchCatalog();
        if (catalog == null) {
            // 目录拿不到就**整批不跑**，不是「这次少抽一类边」。
            // 写入语义是整体替换：商品三元组在半路被丢光之后，那篇文档原有的商品边
            // 会被这次写入删掉，而报告上失败数是 0——看着一切正常，图上少了一整类边。
            // 不跑的话上一版图谱原样留着，代价只是这一批的更新没生效
            log.error("[Graph] 商品目录不可用，本批 {} 篇全部跳过：图谱保持上一版，不写入", total);
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
                // 抽取失败：**不发请求**。写入语义是整体替换，空列表会把这篇文档的旧边清掉。
                // 单篇失败不拖垮整批，但连续失败说明模型侧整体不可用，这时再挨个试下去
                // 只是把同一条错误重复十几行、并且每次都要等满一次模型超时
                failed++;
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    skipped += total - i - 1;
                    log.warn("[Graph] 连续 {} 篇抽取失败，本批中止，其余 {} 篇跳过",
                            consecutiveFailures, total - i - 1);
                    break;
                }
                continue;
            }
            GraphIngestResult result = ingest(doc, triples);
            if (result == null) {
                failed++;
                // 同一条熔断也覆盖写入侧：Neo4j 可 ping 但写入全失败时，每篇都要等满
                // 驱动的重试窗口（默认 30 秒），十几篇挨个等下去就是十几分钟
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
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
        List<ProductSummary> catalog = fetchCatalog();
        if (catalog == null) {
            log.error("[Graph] 商品目录不可用，文档 {} 的图谱不更新（保持上一版，不清空）", docNo);
            return false;
        }
        if (doc == null) {
            boolean ok = ingestRaw(docNo, List.of());
            if (ok) {
                runQuietly("孤立实体清理", () -> knowledgeClient.dropGraphOrphans());
                log.info("[Graph] 文档 {} 已不在语料中，其图谱边已清空", docNo);
            }
            return ok;
        }
        List<GraphTriplePayload> triples = extract(doc, catalog);
        if (triples == null) {
            log.warn("[Graph] 文档 {} 单篇抽取失败，图谱保持上一版", docNo);
            return false;
        }
        if (!ingestRaw(docNo, triples)) {
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
    private boolean ingestRaw(String docNo, List<GraphTriplePayload> triples) {
        try {
            Result<GraphIngestResult> result = knowledgeClient.ingestGraph(GraphIngestPayload.builder()
                    .docNo(docNo)
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

    private GraphIngestResult ingest(Document doc, List<GraphTriplePayload> triples) {
        try {
            Result<GraphIngestResult> result = knowledgeClient.ingestGraph(GraphIngestPayload.builder()
                    .docNo(doc.getId())
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
     * 成分来自模型从正文里抽出的、已经过校验的 {@code CONTAINS} 或 {@code PROVIDES} 边。
     * 两边都是真的，拼起来的这条边也就是真的。
     * <p>
     * <b>只在文档确实抽出成分时才补</b>：一篇抽不出任何成分的文档，硬造一条
     * {@code SPUx -CONTAINS-> ???} 只会往图上灌一个没有依据的节点。这种情况下
     * 商品在覆盖率里显示为「有节点、无边」——那正是需要运营去补正文的信号，
     * 不该被一条编出来的边掩盖。
     * <p>
     * <b>为什么用「文档声明的全部商品」× 「文档抽出的全部成分」做笛卡尔积</b>：
     * 一篇「褪黑素与 GABA 类助眠产品说明书」同时是两个商品的说明书，正文里也同时写了
     * 两种成分。哪条成分属于哪个商品，模型有时会错标商品端——而正文里既然两个商品都在，
     * 两个商品都含这两种成分是这个语料里的正确读法。
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
        // 收集这篇文档里出现的成分名（已经过 knowledge-service 的引文校验，是正文里真有的）。
        //
        // **成分可能出现在任一端，两端都要收。** 原实现只认 `X -CONTAINS-> 成分` 的尾端，
        // 于是「碳酸钙 D3 咀嚼片」这类文档——模型把成分写在头端（`钙 -INTERACTS_WITH-> 四环素类`）
        // 而没有产出任何 CONTAINS——就一条主体边都补不出来，商品在图上继续不存在，
        // 而这正是这次要根治的那 13 个商品里的典型一篇。
        //
        // 这样放宽不会放进幻觉：成分本身仍要过 knowledge-service 的端点与引文双锚定，
        // 这里只是把「文档里确实出现过的成分」这个集合取全。
        Map<String, String> ingredients = new LinkedHashMap<>();
        Map<String, String> ingredientQuotes = new LinkedHashMap<>();
        for (GraphTriplePayload t : triples) {
            if (isCompositionPart(t.getHeadKind())) {
                ingredients.putIfAbsent(t.getHeadName(), t.getHeadLabel());
                if (t.getQuote() != null && !t.getQuote().isBlank()) {
                    ingredientQuotes.putIfAbsent(t.getHeadName(), t.getQuote());
                }
            }
            if (isCompositionPart(t.getTailKind())) {
                ingredients.putIfAbsent(t.getTailName(), t.getTailLabel());
                if (t.getQuote() != null && !t.getQuote().isBlank()) {
                    ingredientQuotes.putIfAbsent(t.getTailName(), t.getQuote());
                }
            }
        }
        if (ingredients.isEmpty()) {
            log.debug("[Graph] 文档 {} 声明了 {} 个主体商品，但没抽出任何成分，不补主体边",
                    doc.getId(), subjects.size());
            return triples;
        }
        // 已经由模型抽出的商品→成分对，避免重复
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (GraphTriplePayload t : triples) {
            existing.add(t.getHeadName() + "\u0000" + t.getTailName());
        }
        List<GraphTriplePayload> out = new ArrayList<>(triples);
        int added = 0;
        for (Long spuId : subjects) {
            String spuKey = key(spuId);
            ProductSummary product = byKey.get(spuKey);
            if (product == null) {
                // 声明的商品不在目录里（下架、或编号错）：不补。补出来的节点永远连不上目录，
                // 查询时表现为「这个商品没有已知相互作用」—— 一个不报错的错误答案
                log.warn("[Graph] 文档 {} 声明的商品 {} 不在目录中，跳过主体边", doc.getId(), spuKey);
                continue;
            }
            for (Map.Entry<String, String> e : ingredients.entrySet()) {
                if (existing.contains(spuKey + "\u0000" + e.getKey())) {
                    continue;
                }
                out.add(GraphTriplePayload.builder()
                        .headKind(EntityKind.PRODUCT.name())
                        .headName(spuKey)
                        .headLabel(product.getName())
                        .relation(GraphRelation.CONTAINS.name())
                        .tailKind(EntityKind.INGREDIENT.name())
                        .tailName(e.getKey())
                        .tailLabel(e.getValue())
                        .quote(ingredientQuotes.get(e.getKey()))
                        // 依据是「文档声明了主体 + 正文里有这个成分」这个组合事实，不是某一句话，
                        // 所以不带 quote，改为置 declared 标志——knowledge-service 的校验据此免引文
                        // 校验（见 TripleValidator，豁免范围只到这一种边）。
                        .declared(true)
                        .build());
                existing.add(spuKey + "\u0000" + e.getKey());
                added++;
            }
        }
        if (added > 0) {
            log.info("[Graph] 文档 {} 按声明的归属补出 {} 条商品→成分边", doc.getId(), added);
        }
        return out;
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
                5b. **只有当一句话把要一起服用的所有东西都点名了，才抽 COMBINED_WITH。**
                   文档写「铁剂与钙剂同服影响吸收」时，那只涉及两样，用
                   「铁剂 -INTERACTS_WITH-> 钙剂」而不是组合；文档写
                   「本品含铁，与钙剂、维生素D 同服会增加结石风险」这种一句话里明确列出
                   三样（及以上）并给出共同后果的，才抽成组合：
                   headKind 填 COMBINATION，headName 用「+」连接全部成员
                   （如 铁剂+钙剂+维生素D），relation 填 COMBINED_WITH，tailName 填被牵连的药物或人群。
                   **成员必须全部出现在 quote 里**：只提到其中两样、第三样是你补上的，
                   整条会被系统丢掉。宁可少抽一条组合，也不要把两样东西的风险说成三样的。
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
