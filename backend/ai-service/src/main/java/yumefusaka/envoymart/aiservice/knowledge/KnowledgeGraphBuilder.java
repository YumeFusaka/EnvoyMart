package yumefusaka.envoymart.aiservice.knowledge;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.graph.EntityKind;
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

    /** 整批构建的结果，供调用方在日志与接口里说清「建了多少」 */
    /**
     * @param failed 既含「抽取失败」也含「写入失败」——两者都不该计入入库数，
     *               但日志里各自有一条 WARN 说明是哪一种。
     *               <b>不要把它读成「抽取失败」</b>：实测踩过一次，写入侧读超时被记成
     *               「抽取失败 1 篇」，于是跑去查模型，而模型那次抽得好好的、
     *               边也真的写进了图里（超时发生在客户端，服务端照写不误）
     */
    public record BuildReport(int documents, int accepted, int rejected, int failed, boolean graphAvailable) {
    }

    /**
     * 为全批文档重建图谱。
     * <p>
     * 产品目录<b>在批开始时取一次</b>：抽取按篇进行，逐篇去拉目录等于把同一份几十条的数据
     * 重复拉十几遍；而目录在一次重建期间不会变（它变了就该重跑重建）。
     */
    public BuildReport build(List<Document> documents) {
        List<ProductSummary> catalog = fetchCatalog();

        int accepted = 0;
        int rejected = 0;
        int failed = 0;
        boolean available = true;
        for (Document doc : documents) {
            List<GraphTriplePayload> triples = extract(doc, catalog);
            if (triples == null) {
                // 抽取失败：**不发请求**。写入语义是整体替换，空列表会把这篇文档的旧边清掉
                failed++;
                continue;
            }
            GraphIngestResult result = ingest(doc, triples);
            if (result == null) {
                failed++;
                continue;
            }
            accepted += result.getAccepted();
            rejected += result.getRejected();
            if (!result.isAvailable()) {
                // 图谱不可用时**立刻停整批**，而不是继续把剩下十几篇挨个试一遍——
                // 那只是把同一条错误重复十几行，还得为每篇白白付一次模型调用。
                available = false;
                log.warn("[Graph] 图谱存储不可用，本批在第 {} 篇中止，其余文档跳过", accepted + failed);
                break;
            }
        }

        if (available) {
            runQuietly("孤立实体清理", () -> knowledgeClient.dropGraphOrphans());
        }
        log.info("[Graph] 图谱构建完成：文档 {} 篇，入库 {} 条，丢弃 {} 条，未入库 {} 篇",
                documents.size(), accepted, rejected, failed);
        return new BuildReport(documents.size(), accepted, rejected, failed, available);
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
            List<GraphTriplePayload> linked = linkProducts(parsed, catalog);
            log.info("[Graph] 文档 {} 抽取 {} 条，商品链接后 {} 条",
                    doc.getId(), parsed.size(), linked.size());
            return linked;
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

    /** 与 knowledge-service 的实体名规范化保持一致：去空白、转小写 */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
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
     */
    private List<GraphTriplePayload> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("[Graph] 抽取结果里找不到 JSON 对象：{}", abbreviate(raw));
            return List.of();
        }
        try {
            Map<String, List<GraphTriplePayload>> parsed = MAPPER.readValue(
                    raw.substring(start, end + 1), new TypeReference<>() {
                    });
            List<GraphTriplePayload> triples = parsed.get("triples");
            if (triples == null || triples.isEmpty()) {
                return List.of();
            }
            return triples.stream()
                    .filter(t -> t != null && t.getRelation() != null)
                    .limit(MAX_TRIPLES)
                    .toList();
        } catch (Exception e) {
            log.warn("[Graph] 抽取结果无法解析：{} | {}", e.getMessage(), abbreviate(raw));
            return List.of();
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    // ==================== 辅助 ====================

    private List<ProductSummary> fetchCatalog() {
        try {
            Result<List<ProductSummary>> result = productClient.catalog();
            if (result == null || result.getCode() == null || result.getCode() != 200 || result.getData() == null) {
                log.warn("[Graph] 商品目录拉取失败：{}，本次不抽取商品相关关系",
                        result == null ? "无响应" : result.getMsg());
                return List.of();
            }
            log.info("[Graph] 商品目录 {} 条，用于实体链接", result.getData().size());
            return result.getData();
        } catch (RuntimeException e) {
            log.warn("[Graph] 商品目录拉取异常：{}，本次不抽取商品相关关系", e.getMessage());
            return List.of();
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
