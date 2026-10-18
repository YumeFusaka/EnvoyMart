package yumefusaka.envoymart.agent.rag;

import yumefusaka.envoymart.agent.llm.ToolExecution;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 医学/营养问题的严格证据闸门。
 *
 * <p>普通引用校验只能证明“句子带了一个合法编号”，不能证明编号指向了同一个
 * 商品、同一条关系或同一类风险。医学内容里这种错位不能用“可能”“仅供参考”
 * 弱化，所以这里采用宁可拒绝也不迁移结论的判据。</p>
 */
public final class MedicalEvidenceVerifier {

    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;\\n]+[。！？!?；;]*");
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PRODUCT_CODE = Pattern.compile("(?i)(SKU|SPU)\\s*0*(\\d+)");
    private static final Pattern MONEY = Pattern.compile("(?:¥|￥)(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)元");
    static final Pattern QUANTITY = MedicalQuantities.QUANTITY;
    private static final Pattern DOSE_REFERENCE = Pattern.compile("(?:这个|该|上述|这一|此)(?:剂量|用量|摄入量|上限)");
    private static final Pattern DOSE_UNIT = Pattern.compile("(?:毫克|微克|公斤|千克|克|粒|片|丸|袋|iu|国际单位)$");
    private static final Pattern DOSING_CONTEXT = Pattern.compile("每日|每天|每次|剂量|用量|摄入|上限|服用|服药|用药|补充|间隔|疗程");
    private static final String RELATION_ACTIONS = "增加|提高|升高|降低|减少|促进|抑制|减弱|增强|影响|阻碍";
    private static final String CLINICAL_OBJECTS = "抗凝作用|抗凝效果|凝血功能|风险|吸收|生物利用度|药效|疗效";
    private static final Pattern MEDICATION_CLASSIFICATION = Pattern.compile("(不是|并非|不属于|不为|不算是|不算|是|属于|为)(非处方药|处方(?:抗凝)?药)");
    private static final Pattern MONITORING = Pattern.compile("(?<negativeBefore>无需|不需要|不必|不用|不应|不能|不)?监测(?<metric>凝血指标|凝血功能|血糖|血压)(?<negativeAfter>没有必要|无必要|不必要)?");
    private static final Pattern LIMITED_SEARCH = Pattern.compile("(?:本次|当前|这一轮)(?:检索|查询|图谱检查|检查).*(?:未查到|没有查到|未找到|没有找到)");
    private static final Pattern ABSENCE = Pattern.compile("(?:没有|未)(?:收录|涉及|提到)|知识库.*没有.*(?:相互作用|含量)");
    private static final Pattern GRAPH_ABSENCE = Pattern.compile("^(?:平台)?(?:知识)?图谱(?:中)?(?:没有|未)收录.*");
    private static final Pattern PRODUCT_PRONOUN = Pattern.compile("本品|该商品|该产品");
    /**
     * 「本品」指代的是<b>说明书自身的成分表</b>，不是商品身份：「对本品任一成分过敏者禁用」
     * 是每一份说明书的通用禁忌，换一份说明书这句话照样成立。它不承担「这个成分属于哪个商品」
     * 的断言，因此归属由**这句引用的切片**唯一确定即可。
     * <p>
     * 与之相对，「本品所含钙可影响…」「本品为蛋白质类补充剂」是在陈述<b>商品本身</b>的属性，
     * 同一句话换到另一份说明书上就不成立——这类仍必须由句子自己（或前文）声明来源，
     * KG-02/KG-11 守的正是这条线。
     */
    private static final Pattern PRODUCT_COMPOSITION_PRONOUN = Pattern.compile(
            "本品(?:任一|任何|所有|全部)?(?:成分|配料|原料)|对本品(?:任一|任何)?成分");
    /** 成对回指（「两者/二者」）指向同段前文已出现的关系参与组，不是待补全的剂量前指。 */
    private static final Pattern PAIR_PRONOUN = Pattern.compile("两者|二者");
    /**
     * 「来源展示框架被编号化」的形态：{@code [n]第四章写明：} 或 {@code [n]中写明：}。
     * <p>
     * 句子原本写的是《文档名》第四章写明：…，引用编号化把《文档名》换成了 [n]，
     * 框架动词留了下来。它证明这句话<b>自己声明了来源</b>，只是来源的名字变成了编号——
     * 归属因此可以改由引用切片的元数据给出（见 {@code invalidReason} 的「本品」判据）。
     * <p>
     * 匹配面刻意收得很窄：必须紧跟着「写明/指出/说明/提到/记载」这类框架动词，
     * 中间不允许夹进别的内容。放宽成「句首有 [n]」会把「[1]本品所含钙…」这类
     * 根本没声明来源的句子一并放行，那正是 KG-02/KG-11 守的那条线。
     */
    private static final Pattern NUMBERED_SOURCE_FRAME = Pattern.compile(
            "^\\s*\\[\\d+]\\s*(?:第[一二三四五六七八九十百\\d]+[章节条款]|正文|原文|其中|该条|本条)?"
                    + "(?:中|里|内)?(?:也)?(?:写明|指出|说明|提到|记载|明确)[：:]");

    /**
     * 章节引用框架：`第四章写明：…` —— 与 {@link #NUMBERED_SOURCE_FRAME} 是同一件事的第三种写法。
     * <p>
     * 三种写法的来源都是「句子自己声明的出处」，区别只在书写形式：
     * <pre>
     * 《维生素 K2 软胶囊产品说明书》第四章写明：…   ← 标题形态（reportedSource 分支）
     * [1]第四章写明：…                              ← 编号形态（NUMBERED_SOURCE_FRAME）
     * 第四章写明：…                                 ← 章节形态（本模式）
     * </pre>
     * 第三种出现在真实链路里：标题被 {@code numberTitles} 换成 `[1]` 之后，若该编号
     * 随后被挪到句末或去掉，句首就只剩下章节号。判据不该因为书写形式的变化而失效。
     * <p>
     * <b>边界不放宽</b>：仍然要求句子自己带来源框架动词（写明/指出/说明/提到/记载/明确）。
     * 「本品所含钙可影响…[1]」这种没有任何框架声明的句子照样要过归属检查——
     * 引用的切片是说明书不等于句子说了「本品」就是那一份（KG-02/KG-11 两个用例守的正是这条）。
     */
    private static final Pattern SECTION_SOURCE_FRAME = Pattern.compile(
            "^\\s*(?:第[一二三四五六七八九十百\\d]+[章节条款]|正文|原文|其中|该条|本条)"
                    + "(?:中|里|内)?(?:也)?(?:写明|指出|说明|提到|记载|明确)[：:]");
    private static final Pattern TOPIC_FRAME = Pattern.compile("^(?:现在|接下来)?(?:讨论|说回|关于|至于|针对).*");
    private static final Pattern RELATION_DIRECTION = Pattern.compile(
            "(?:(不会|不可能|不|未|没有)(?:再|明显|显著|直接)?)?"
                    + "(" + RELATION_ACTIONS + ")"
                    + "((?:(?!" + RELATION_ACTIONS + ")[^，,。；;！？!?\\n])*?)(" + CLINICAL_OBJECTS + ")");
    private static final Pattern CLINICAL_POSTFIX = Pattern.compile(
            "((?:出血|血栓|高钙血症|肾结石)?风险|抗凝作用|抗凝效果|凝血功能|吸收|生物利用度|药效|疗效)(不会|不能|不|没有|未)?(?:可|可能|会)?(?:明显|显著)?(变差|下降|降低|减少|减弱|打折扣|提高|增加|增强|上升)");
    private static final Pattern RELATION_CLAUSE_BOUNDARY = Pattern.compile(
            "[。！？!?；;，,\\n]+|(?=但是|但|然而|而)"
                    + "|(?=(?:并且|同时|此外|另外|且|并|及|和)[^。！？!?；;，,\\n]*(?:" + RELATION_ACTIONS + "))");
    private static final Pattern INTERACTION_PRESENCE = Pattern.compile(
            "(没有|无|不存在|存在|有)(?:明确|任何|已知)?相互作用");
    private static final Pattern EFFECT_PRESENCE = Pattern.compile(
            "(不会|不能|不|没有)?(?:可能|会|可)?(?:相互)?影响");
    private static final Pattern ABSORPTION_COMPETITION = Pattern.compile("(?:不存在|没有|无)?吸收竞争");
    /**
     * 「吸收被阻碍」的另一种写法：原文用形容词（「形成难吸收的络合物」），
     * 生成侧复述成动词短语（「导致药物吸收下降」）。
     * <p>
     * 极性判据要认的是<b>极性本身</b>，不是书写形式——两种写法陈述的是同一条结论
     * （钙把四环素类结合成无法吸收的络合物）。只认动词短语会让「引文写难吸收、
     * 复述写吸收下降」被判「关系对象或极性与引用切片不一致」，而两边说的是同一件事。
     * 判据只覆盖这一条明确语义，不做同义泛化。
     */
    private static final Pattern POOR_ABSORPTION = Pattern.compile(
            "(?:难|不易|难以|妨碍|阻碍|干扰)(?:以)?吸收|吸收(?:困难|受阻|受抑制)");
    private static final Pattern RISK_PRESENCE = Pattern.compile(
            "(没有|无|不存在|存在|有)([^，,。；;！？!?]{0,12}?风险)");
    private static final Pattern SAFE_COMBINATION = Pattern.compile(
            "安全(?:同服|合用|一起吃)|(?:同服|合用|一起吃)(?:是|属于)?安全(?:的)?|没有冲突");
    /**
     * 售后/物流/订单政策里的时间量不是医学剂量：`七天无理由退货`、`72 小时发货`
     * 会被 {@link MedicalQuantities#hasUnparsedQuantity} 当成未解析剂量，把整句
     * 误判成医学事实并送进严格证据门（G-15 的真实售后回答因此被记成错误引用）。
     * 判据要求「政策动作词 + 时间量」同时出现，且没有临床信号；只有政策词或
     * 只有时间量的句子不豁免。
     */
    private static final Pattern NON_CLINICAL_POLICY = Pattern.compile(
            "(?:退货|退款|退换|售后|签收|配送|发货|物流|订单|无理由).{0,12}(?:天|日|小时|工作日)"
                    + "|(?:天|日|小时|工作日).{0,12}(?:退货|退款|退换|售后|签收|配送|发货|物流|订单|无理由)");
    private static final Pattern CLINICAL_POLICY_OVERRIDE = Pattern.compile(
            "服用|服药|用药|剂量|用量|摄入|出血|风险|相互作用|禁忌|过敏|不良反应|副作用|治疗|疗效|吸收");
    /**
     * 症状警示句：给出可核对症状列表与行动建议，如“出现异常出血、皮肤瘀斑等表现应及时就医”。
     * 通用提醒（“如有疑问请咨询医师”）没有症状中介语，不进入事实句分母与核验。
     */
    private static final Pattern SYMPTOM_WARNING = Pattern.compile(
            "(?:出现|发生|若有|如有|若出现|如出现|一旦出现)(?<symptoms>[^，,。；;！？!?\\n]{2,60}?)(?:等)?(?:表现|症状|不适|反应|情况)(?:时)?[，,]?(?<action>(?:应|需|须|要|请|建议|务必)[^，,。；;！？!?\\n]{1,30})");
    /**
     * 安全警示句：超量、过量、大剂量、中毒、蓄积、掩盖、不良反应、副作用等。
     * 这些结论写错一位就会误导用户，必须逐片复核引用强度；普通膳食建议不在此列。
     */
    private static final Pattern SAFETY_WARNING = Pattern.compile(
            "超量|过量|大剂量|中毒|蓄积|掩盖|不良反应|副作用|血钙升高|延误[^，,。；;！？!?\\n]{0,12}损害");
    /**
     * 纯剂量/用法句的逐片复核只要求「不明显弱于本轮最强依据」，不要求它自己跨过绝对阈值。
     */
    private static final double RELATIVE_CHUNK_SCORE_FLOOR = 0.60;
    /** 版本优先裁定句：改变“以哪一份资料为准”的结论，必须由同一引用切片给出替代授权。 */
    private static final Pattern VERSION_MENTION = Pattern.compile("v\\d{4}(?:\\.\\d+)?");
    private static final Pattern VERSION_DECISION = Pattern.compile(
            "(?:按|以|取|选|采用|选用|依据)[^，,。；;！？!?\\n]{0,12}(?:较新|最新|更新|新版|新版本|旧版|版本)"
                    + "|(?:较新|最新|新版|新版本)[^，,。；;！？!?\\n]{0,12}(?:为准|优先|执行|采用|适用|有效)");
    private static final Pattern VERSION_NEGATED_SUPERSEDE = Pattern.compile("(?:不能|不可|无法|难以)替代");
    private static final Pattern VERSION_SUPERSEDE = Pattern.compile(
            "(?:明确|应当|应)?(?:替代|取代|作废|废止|更替)"
                    + "|以[^，,。；;！？!?\\n]{0,12}(?:为准|优先)"
                    + "|按[^，,。；;！？!?\\n]{0,12}(?:新版|最新版本|较新版本)(?:说明书)?执行");

    private static final List<String> MEDICAL_TERMS = List.of(
            "磷虾油", "鱼油", "深海鱼油", "藻油", "DHA", "EPA", "维生素D3", "维生素D2",
            "维生素D", "维生素K", "维生素", "钙片", "钙剂", "铁叶酸片", "铁剂", "叶酸",
            "益生菌", "乳清蛋白", "蛋白粉", "褪黑素", "辅酶Q10", "左旋多巴", "甲状腺素", "华法林",
            "铁", "钙", "镁", "镁剂", "锌", "锌剂", "硒", "硒剂", "保健食品", "膳食补充剂",
            "阿司匹林", "氯吡格雷", "抗凝药", "抗凝药物", "抗血小板药", "抗血小板药物",
            "噻嗪类利尿剂", "强心苷", "四环素类", "喹诺酮类", "孕期", "妊娠期", "哺乳期",
            "儿童", "肾功能", "肝功能", "出血性疾病");

    /**
     * 商品字段的**标签词**——模型为可读性给字段起的名字，回执里没有也不该有。
     *
     * <p>它们不参与「句子里有没有回执之外的内容」核对（见 nothingBeyondReceipt）。
     * 这与「格式词」是两件事：格式词（瓶/盒/件/元）出现在值里，标签词只出现在冒号左边。
     */
    private static final Set<String> FIELD_LABELS = Set.of(
            "规格", "成分", "价格", "适用", "适用人群", "编号", "商品", "候选", "品牌", "类目",
            "标签", "库存", "是否含乳糖", "侧重", "作用", "提醒", "说明", "名称", "单价", "包装");

    private static final Map<String, String> MEDICATION_CLASS_ALIASES = medicationClassAliases();
    private static final Pattern MEDICATION_CLASS_COORDINATION = medicationClassCoordination();

    private static final Map<String, Pattern> RELATIONS = relationPatterns();

    private MedicalEvidenceVerifier() {
    }

    public record Verdict(boolean medical, List<String> unsupported, Map<String, String> reasons) {
        public static Verdict notApplicable() {
            return new Verdict(false, List.of(), Map.of());
        }
    }

    public static Verdict verify(String userMessage, String reply, List<DocumentChunk> evidence) {
        return verify(isMedicalQuestion(userMessage), reply, evidence);
    }

    public static Verdict verify(boolean medicalQuestion, String reply, List<DocumentChunk> evidence) {
        return verify(medicalQuestion, reply, evidence, List.of());
    }

    public static Verdict verify(boolean medicalQuestion, String reply, List<DocumentChunk> evidence,
                                 List<ToolExecution> tools) {
        return verify(medicalQuestion, reply, evidence, tools, EvidenceGate.Thresholds.defaults(), java.util.Set.of());
    }

    /**
     * 带请求级证据门的医学核对。证据列表编号保持不变，只把低于同一门槛的引用
     * 标成不可支撑，避免为了过滤弱片段而重排编号。
     */
    public static Verdict verify(boolean medicalQuestion, String reply, List<DocumentChunk> evidence,
                                 List<ToolExecution> tools, EvidenceGate.Thresholds thresholds,
                                 java.util.Set<String> graphChunkIds) {
        if (!medicalQuestion || reply == null || reply.isBlank()) {
            return Verdict.notApplicable();
        }
        List<DocumentChunk> safeEvidence = evidence == null ? List.of() : evidence;
        // 证据门只对**整轮**判一次：它回答的是「这一轮证据够不够回答这个问题」。
        // 逐句再按引用切片重判一次，是同一事实两处各算——短句/口语化转述的重排分
        // 天然偏低，会把原文逐字支撑的结论误杀（2026-10-18 alice 便秘会话）。
        boolean roundSufficient = thresholds == null
                || EvidenceGate.evaluate(safeEvidence, thresholds,
                graphChunkIds == null ? Set.of() : graphChunkIds).isSufficient();
        List<String> unsupported = new ArrayList<>();
        Map<String, String> reasons = new LinkedHashMap<>();



        String previousDose = null;
        Set<String> previousSubjects = Set.of();
        Set<String> previousPopulation = Set.of();
        Set<String> headingSubjects = Set.of();
        String ownerContext = "";
        Set<String> currentGraphScope = Set.of();
        int previousEnd = 0;
        Matcher sentences = SENTENCE.matcher(reply);
        while (sentences.find()) {
            String sentence = sentences.group();
            if (reply.substring(previousEnd, sentences.start()).contains("\n\n")) {
                previousDose = null; previousSubjects = Set.of(); previousPopulation = Set.of(); currentGraphScope = Set.of();
            }
            previousEnd = sentences.end();
            if (sentence.isBlank()) continue;
            String antecedent = previousDose;
            Set<String> antecedentSubjects = previousSubjects;
            Set<String> antecedentPopulation = previousPopulation;
            previousDose = null;
            previousSubjects = Set.of();
            previousPopulation = Set.of();
            String heading = MedicalText.visible(sentence).trim();
            if (TOPIC_FRAME.matcher(normalize(sentence)).matches()) ownerContext = "";
            List<String> declaredOwners = safeEvidence.stream().map(chunk -> chunk == null ? null : productOwner(chunk.getTitle()))
                    .filter(java.util.Objects::nonNull).distinct().filter(owner -> normalize(sentence).contains(owner)).toList();
            if (!declaredOwners.isEmpty()) ownerContext = declaredOwners.size() == 1 ? declaredOwners.getFirst() : "";
            if ((sentence.trim().matches("^\\*\\*.*\\*\\*$") || sentence.trim().startsWith("#")) && !isMedicalFact(sentence)) {
                ownerContext = declaredOwners.size() == 1 ? declaredOwners.getFirst() : "";
                headingSubjects = MedicalDoseAssertions.entities(heading.replaceFirst("^#+\\s*", ""), MEDICAL_TERMS);
                currentGraphScope = Set.of();
                continue;
            }
            if (isGraphScopeIntro(sentence)) currentGraphScope = graphScopeSubjects(sentence);
            // 引导句（「维生素D的推荐量和上限如下：」）与标题一样声明了后续正文的主体：
            // 它自己不含量值、不算医学事实句，但省略主体的列表项要能接住它。
            // 与标题同一条边界——只补主体，不补数值。
            if (isSubjectLeadIn(sentence)) {
                Set<String> declared = MedicalDoseAssertions.entities(MedicalText.visible(sentence), MEDICAL_TERMS);
                if (!declared.isEmpty()) headingSubjects = declared;
                currentGraphScope = Set.of();
                continue;
            }
            if (isScopedGraphAbsence(sentence, currentGraphScope)) continue;
            // 澄清问句是向用户提问，不是向用户断言——它没有出处可言，也不该被当医学事实核验。
            // 判据见 isQuestionToUser：带具体数值/编号的疑问句仍在确认事实，不在此列。
            if (isQuestionToUser(sentence)) continue;
            if (boundaryReminder(sentence)) {
                continue;
            }
            List<Integer> refs = refs(CitationVerifier.numberTitles(sentence, safeEvidence), safeEvidence.size());
            if (supportedProductRow(sentence, tools)) {
                continue;
            }
            if (refs.isEmpty()) {
                if (isMedicalFact(sentence)) {
                    unsupported.add(sentence.trim());
                    reasons.put(sentence.trim(), "医学事实句没有有效引用");
                }
                continue;
            }
            boolean doseReference = DOSE_REFERENCE.matcher(normalize(sentence)).find();
            // 「两者/二者」是句内回指，不是「这个剂量」式的前向指代：它指向同一段前文
            // 已经出现的关系参与组，引用切片里的间隔句本身可以完整支撑。把成对回指
            // 也当 DOSE_REFERENCE，会让 G13 的真实 v7 回答在「两者应间隔至少 2 小时」
            // 这一句上被要求再找一次 antecedent，最终判「剂量指代缺少同段已支撑的
            // 唯一实体与数值」——关系、数值、引用都对，只是回指形式不同。
            boolean pairReference = PAIR_PRONOUN.matcher(normalize(sentence)).find();
            boolean selfContainedDose = (quantities(sentence).size() == 1 && hasMedicalTerm(sentence)) || pairReference;
            String claim = sentence.trim();
            List<String> ancestors = CitationVerifier.listAncestors(reply, sentences.start());
            boolean listSyntax = claim.matches("(?s)^(?:[-*+]|\\d+[.)])\\s+.*") || sentence.matches("(?s)^[ \\t]+.*");
            boolean dependentListItem = listSyntax && isDependentListSentence(claim);
            Set<String> listSubjects = new LinkedHashSet<>();
            if (dependentListItem) for (String parent : ancestors) {
                String label = MedicalText.visible(parent).split("[：:]", 2)[0];
                listSubjects.addAll(MedicalDoseAssertions.entities(label, MEDICAL_TERMS));
            }
            String reason;
            if (dependentListItem && listSubjects.isEmpty()) {
                reason = "列表子项缺少原文结构内明确父项主体";
            } else if (doseReference && !selfContainedDose && antecedent == null) {
                reason = "剂量指代缺少同段已支撑的唯一实体与数值，不能借引用原文补全";
            } else {
                // 前置事实也必须被当前同一引用切片支撑，不能把两个片段拼成新的剂量结论。
                if (doseReference && !selfContainedDose) claim = antecedent + " " + claim;
                if (dependentListItem) claim = String.join("与", listSubjects) + claim.replaceFirst("^(?:[-*+]|\\d+[.)])\\s*", "");
                // 主体继承三个来源，就近优先：句内自己声明（MedicalDoseAssertions 在 claim 里解析）
                // > 上一句已支撑的剂量句（承前省略）> 标题行/引导句声明的章节主体。
                // 第三条此前只服务 MONITORING 分支，于是「**维生素D的推荐摄入量与上限** + 正文省略主体」
                // 这种最自然的 Markdown 写法被判「数量主体不完整」——同一问题 12 轮里 4 轮因此被整篇
                // 兜底（N1 文档 §2.13 发现 M）。只补主体，不补数值：继承来的主体仍要过
                // 数值/人群/角色/约束四项逐项核对，切片里没有这个主体照样拒。
                Set<String> scopedSubjects = MONITORING.matcher(normalize(claim)).find() && !headingSubjects.isEmpty()
                        ? headingSubjects
                        : !antecedentSubjects.isEmpty() ? antecedentSubjects : headingSubjects;
                reason = invalidReason(claim, refs, safeEvidence, thresholds, graphChunkIds,
                        roundSufficient, scopedSubjects, antecedentPopulation, ownerContext);
            }
            if (reason != null) {
                String key = sentence.trim();
                unsupported.add(key);
                reasons.put(key, reason);
            } else if (isMedicalFact(claim)) {
                previousSubjects = MedicalDoseAssertions.entities(claim, MEDICAL_TERMS);
                if (previousSubjects.isEmpty()) previousSubjects = antecedentSubjects;
                var scope = MedicalDoseAssertions.scope(claim, MEDICAL_TERMS, antecedentSubjects, antecedentPopulation);
                if (!scope.subjects().isEmpty()) {
                    previousSubjects = scope.subjects();
                    previousPopulation = scope.population();
                }
                if (quantities(claim).size() == 1 && !previousSubjects.isEmpty() && DOSING_CONTEXT.matcher(normalize(claim)).find()) previousDose = claim;
            }
        }
        var closure = CitationVerifier.removeRejectedWithListDependencies(reply, unsupported, child ->
                !isDependentListSentence(child) && !factSentences(child).isEmpty()
                        && verify(true, child, safeEvidence, tools, thresholds, graphChunkIds).unsupported().isEmpty());
        for (String child : closure.dependentSentences()) {
            unsupported.add(child);
            reasons.put(child, "列表父项被拒，依赖子项联合剔除");
        }
        return new Verdict(true, List.copyOf(new LinkedHashSet<>(unsupported)), Map.copyOf(reasons));
    }

    /** 列表符和缩进只是排版；省略参与主体的续行仍依赖原结构内的父项。 */
    public static boolean isDependentListSentence(String sentence) {
        String visible = MedicalText.visible(sentence).trim().replaceFirst("^(?:[-*+]|\\d+[.)])\\s*", "");
        if (!visible.matches("(?s)^(?:与|和|及|合用|同服).*")) return false;
        String[] labeled = visible.split("[：:]", 2);
        if (labeled.length < 2) return true;
        String body = labeled[1].trim();
        // 标签只描述展示对象；正文显式给出参与主体与临床谓词时不需要借父项，仍由医学闸门核验全文。
        Set<String> explicit = MedicalDoseAssertions.entities(body, MEDICAL_TERMS);
        List<RelationFact> facts = relationFacts(body, Set.of());
        return explicit.size() < 2 || facts.isEmpty()
                || facts.stream().anyMatch(fact -> fact.entities().isEmpty() || fact.requiresPair() && fact.entities().size() < 2);
    }

    /**
     * 临床硬信号：用户问的是**医学结论**（剂量、相互作用、风险、禁忌），出现即判医学。
     * <p>
     * <b>为什么不含「补充剂 / 保健品 / 药品 / 药物」这类类别名词</b>：它们标识的是**医学实体**，
     * 不是「在问医学结论」这个行为。把它们算成硬信号，会让「推荐一款维生素D补充剂」这类
     * 纯选购句在第二层之前就被判成医学问题——那正是 N1 要修的第一类现象。
     */
    private static final Pattern CLINICAL_SIGNAL = Pattern.compile(String.join("|",
            "推荐摄入", "最高摄入", "可耐受", "摄入上限", "每日上限", "每天上限",
            "相互作用", "同服", "同吃", "合用", "一起用", "一起吃", "服药", "用药", "服用", "剂量", "用量",
            "(?:每天|每日|每次).{0,6}(?:几|多少)(?:粒|片|颗|袋|毫克|微克|克)",
            "(?:多久|多长时间).{0,6}(?:吃|服用)",
            "(?:吃|服用).{0,4}(?:几|多少)(?:粒|片|颗|袋|毫克|微克|克)",
            "^(?:那)?(?:几|多少)(?:毫克|微克|克)[?？]?$",
            "(?:每天|每日|每次).{0,6}(?:吃|喝|服用|补|摄入).{0,4}(?:几|多少)",
            "能不能吃", "可不可以吃", "能吃吗", "能不能喝", "可以喝吗", "禁忌", "副作用", "不良反应",
            "过量", "超量", "中毒",
            "会不会", "有没有.{0,8}(?:影响|冲突|风险|作用|关系|禁忌|相互作用)",
            "间隔", "错开", "隔开", "相隔",
            "(?:影响|降低|增加|减少|增强|削弱|干扰|妨碍).{0,8}(?:吸收|药效|疗效|效果|生物利用度|抗凝|凝血|代谢)",
            "吃什么药", "能吃什么"));

    /**
     * 明确选购 / 检索语境：用户在**挑商品、问规格价格、执行加购**，不是问医学结论。
     * <p>
     * 只在**临床硬信号缺席**时才生效——「我在补钙，同时吃抗生素怎么安排时间」既含
     * 「安排时间」（不命中本层）也含「补钙」「抗生素」，但它命中了硬信号里的「相互作用」类，
     * 所以仍然走医学链。顺序反过来的话，一句真正的用药咨询会被「推荐」两个字放走。
     */
    private static final Pattern COMMERCE_CONTEXT = Pattern.compile(String.join("|",
            "搜索", "搜一下", "搜下", "查找", "找一款", "找几款", "改搜", "展示", "列出", "列一下", "列出来",
            "推荐", "哪款", "哪一款", "哪个好", "有什么推荐", "有没有推荐",
            "加入购物车", "加购", "下单", "结算", "购买", "买一", "买点", "来一份", "来一个", "各来", "帮我买",
            "候选", "多少钱", "有货", "库存", "销量", "已售", "评价", "品牌", "评分",
            // 「规格」只认问句形态：它是**属性名词**，出现在「两份说明书规格不一样」里时问的
            // 是文档内容而不是在挑商品——放它过去会把医学问题判成非医学（G06 实证）。
            "有什么规格", "有哪些规格", "哪些规格", "什么规格", "几个规格", "多种规格", "各规格",
            "规格可以选", "规格能选", "规格可以挑", "规格能挑"));

    /**
     * 这是不是一个**医学问题**——判据只回答「用户是不是在问医学结论」，不回答「这句话里有没有医学词」。
     * <p>
     * <b>三层的顺序是判据本身，不是实现细节</b>（顺序换了结论就变）：
     * <ol>
     *   <li><b>临床硬信号</b> → 医学。剂量、相互作用、风险、禁忌属于安全性断言，必须确定性命中，
     *       不能交给概率系统（见改造方案 §4.1「硬约束为什么不能交给判官」）。</li>
     *   <li><b>明确选购语境</b> → 非医学。「搜索钙片，至少展示3个候选」「推荐一款维生素D补充剂」
     *       里的医学词标识的是**商品**，用户在挑商品。实测这类句子有 164 轮被旧词表判成医学问题，
     *       整篇走兜底拒答（N1 §2.14 发现 T/V）。</li>
     *   <li><b>其余沿用既有词表</b> → fail-safe。「维生素D软胶囊开封后怎么贮存」这类既不命中硬信号、
     *       也不像选购的句子，保持改造前的判定，不因新能力缺失而放宽。</li>
     * </ol>
     * <b>为什么第三层不能删</b>：词表是兜底，不是主判据。删掉它，所有「没写剂量也没说买」的医学问句
     * （例如「维生素 D 对骨骼有什么好处」）都会掉到非医学侧。
     * <p>
     * <b>判据只读用户本轮原话</b>——改写后的检索句是给检索器用的中间产物（R20/R23）。
     */
    public static boolean isMedicalQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = normalize(question);
        if (CLINICAL_SIGNAL.matcher(normalized).find()) {
            return true;
        }
        if (COMMERCE_CONTEXT.matcher(normalized).find()) {
            return false;
        }
        return MEDICAL_TERMS.stream().anyMatch(term -> normalized.contains(normalize(term)))
                || normalized.matches(".*(?:推荐摄入|最高摄入|可耐受|摄入上限).*" )
                || normalized.matches(".*(?:相互作用|同服|服药|用药|服用|剂量|药品|药物|抗生素|补充剂|保健品|头疼|头痛|肚子不舒服|腹痛|睡不好|失眠|发烧|发热|咳嗽|腹泻|便秘|过敏|恶心|呕吐|吃什么药|能吃什么|一起吃|一起用|合用|吃了以后).*" )
                || Pattern.compile("(?:每天|每日|每次).{0,6}(?:几|多少)(?:粒|片|颗|袋|毫克|微克|克)"
                        + "|(?:多久|多长时间).{0,6}(?:吃|服用)|(?:吃|服用).{0,4}(?:几|多少)(?:粒|片|颗|袋|毫克|微克|克)"
                        + "|^(?:那)?(?:几|多少)(?:毫克|微克|克)[?？]?$"
                        + "|(?:每天|每日|每次).{0,6}(?:吃|喝|服用|补|摄入).{0,4}(?:几|多少)" ).matcher(normalized).find();
    }

    /** 查询边界与遵医嘱提醒没有处方结论，不因提醒中提及风险或引用弱片段而误删。 */
    private static boolean boundaryReminder(String sentence) {
        String text = normalize(CITATION.matcher(sentence).replaceAll(""))
                .replaceAll("[\\p{P}\\p{S}\\p{M}]", "");
        if (text.matches("(?:平台知识图谱中|图谱中|平台)?(?:未收录|没有收录|没有查到|没有证据|缺少依据)不等于"
                + "(?:无风险|安全|可安全合用|可安全同服|没有冲突)(?:或(?:无风险|安全|可安全合用|可安全同服|没有冲突))*")) {
            return true;
        }
        return !isMedicalFact(sentence) && text.matches(
                "(?:所有)?(?:用药|服药|治疗)(?:安排)?请(?:务必)?遵医嘱(?:尤其(?:存在)?慢性病或多药联用情况时)?");
    }

    private static boolean isMedicalFact(String sentence) {
        String text = normalize(sentence);
        // 售后/物流政策不是医学事实；先于数量解析排除，避免「七天」被当成剂量。
        // 若同句还有临床信号（服用/出血/风险…），仍按医学事实核验。
        if (NON_CLINICAL_POLICY.matcher(text).find() && !CLINICAL_POLICY_OVERRIDE.matcher(text).find()) return false;
        // 格式不能免除事实的证据责任；纯表头无事实词，自然不会命中。
        Set<String> amounts = quantities(text);
        boolean dose = amounts.stream().anyMatch(amount -> DOSE_UNIT.matcher(amount).find())
                || (!amounts.isEmpty() && (DOSING_CONTEXT.matcher(text).find()
                || hasMedicalTerm(text) && !text.matches(".*(?:退货|退款|签收|配送|发货|订单|合同).*")));
        boolean absence = ABSENCE.matcher(text).find() && !LIMITED_SEARCH.matcher(text).find()
                && (hasMedicalTerm(text) || text.matches(".*(?:知识库|具体含量|相互作用条目).*"));
        return dose || absence || MEDICATION_CLASSIFICATION.matcher(text).find() || MONITORING.matcher(text).find()
                || MedicalQuantities.hasUnparsedQuantity(text) || DOSE_REFERENCE.matcher(text).find() || RELATION_DIRECTION.matcher(text).find()
                || RISK_PRESENCE.matcher(text).find() || INTERACTION_PRESENCE.matcher(text).find()
                || hasMedicalEffect(text) || CLINICAL_POSTFIX.matcher(text).find() || ABSORPTION_COMPETITION.matcher(text).find()
                || text.matches(".*(禁用|不宜使用|拮抗|安全.*(同服|合用|一起吃)|(同服|合用|一起吃).*安全|放心.*(服用|吃)|(可以|能够).*(同服|合用|一起吃|服用)|无风险|没有冲突|不存在相互作用).*" )
                || SYMPTOM_WARNING.matcher(text).find() || isVersionDecision(text);
    }

    private static boolean hasMedicalEffect(String text) {
        Matcher effects = EFFECT_PRESENCE.matcher(text);
        while (effects.find()) {
            String object = CITATION.matcher(text.substring(effects.end())).replaceAll("")
                    .replaceAll("[\\p{P}\\p{S}]", "");
            if (object.matches("^(?:配送|发货|价格|选择|订单|退款|结算|付款|库存|销量|评价).*")) continue;
            if (object.isEmpty() || hasMedicalTerm(text)
                    || object.matches("^(?:吸收|生物利用度|药效|疗效|抗凝|血小板|出血).*")) return true;
        }
        return false;
    }

    /** 医学审核与评测共用事实识别；不把订单金额、合同期限或纯提醒作为医学事实。 */
    public static List<String> factSentences(String reply) {
        if (reply == null || reply.isBlank()) return List.of();
        List<String> facts = new ArrayList<>();
        Matcher sentences = SENTENCE.matcher(reply);
        Set<String> currentGraphScope = Set.of();
        int previousEnd = 0;
        while (sentences.find()) {
            String sentence = sentences.group().trim();
            if (reply.substring(previousEnd, sentences.start()).contains("\n\n")) currentGraphScope = Set.of();
            previousEnd = sentences.end();
            if (isGraphScopeIntro(sentence)) currentGraphScope = graphScopeSubjects(sentence);
            if ((sentence.matches("^\\*\\*.*\\*\\*$") || sentence.startsWith("#")) && !isMedicalFact(sentence)) currentGraphScope = Set.of();
            if (isScopedGraphAbsence(sentence, currentGraphScope)) continue;
            if (isMedicalFact(sentence) && !boundaryReminder(sentence)) facts.add(sentence);
        }
        return List.copyOf(facts);
    }

    /**
     * 引导句：以冒号结尾、不含量值、只声明「接下来要讲哪个主体的什么」。
     * <p>
     * 判据刻意保守——<b>含量值的句子不算</b>（那已经是断言，走正常核验），
     * <b>过长或含多个分句的句子不算</b>（可能是陈述而非引导）。只在
     * 「短句 + 冒号结尾 + 无量值」这一精确形态下把它当作主体声明。
     */
    private static boolean isSubjectLeadIn(String sentence) {
        String visible = MedicalText.visible(sentence).trim();
        if (visible.length() > 60 || !visible.matches("(?s).*[：:]\\s*$")) return false;
        if (!quantities(visible).isEmpty()) return false;
        // 列表项是「内容」，不是「引导」——图谱关系父项（「- **华法林**：…相互作用记录：」）
        // 正是以冒号结尾，若按引导句跳过核验，父项与整棵子树都会被放行（KG-04 三条用例守着它）。
        if (visible.matches("(?s)^(?:[-*+]|\\d+[.)])\\s+.*")) return false;
        // 引导句是纯声明性的：一旦它自己就是医学事实句，说明已经在陈述内容，必须照常核验。
        if (isMedicalFact(visible)) return false;
        return !MedicalDoseAssertions.entities(visible, MEDICAL_TERMS).isEmpty();
    }
    private static boolean isGraphScopeIntro(String sentence) {
        return normalize(sentence).matches("^(?:仅依据|根据|仅根据)本次图谱(?:检查|查询)(?:结果)?[,，].*无法判断.*")
                && !isMedicalFact(sentence);
    }

    private static Set<String> graphScopeSubjects(String sentence) {
        Matcher subjects = Pattern.compile("无法判断(.+?)(?:的)?(?:具体)?相互作用").matcher(normalize(sentence));
        return subjects.find() ? MedicalDoseAssertions.entities(subjects.group(1), MEDICAL_TERMS) : Set.of();
    }

    private static boolean isScopedGraphAbsence(String sentence, Set<String> scope) {
        String text = normalize(sentence);
        if (scope.isEmpty() || !GRAPH_ABSENCE.matcher(text).matches()) return false;
        String absent = text.replaceFirst("^(?:平台)?(?:知识)?图谱(?:中)?(?:没有|未)收录", "");
        Set<String> subjects = MedicalDoseAssertions.entities(absent, MEDICAL_TERMS);
        return !subjects.isEmpty() && scope.containsAll(subjects) && !isMedicalFact(absent);
    }

    /** 仅修复已能由一个强引用完整证明的医学句；不搜索未引用来源或补写事实。 */
    public static Map<String, List<Integer>> supportedCitationRepairs(String reply, List<DocumentChunk> evidence,
                                                                   EvidenceGate.Thresholds thresholds, Set<String> graphChunkIds) {
        Map<String, List<Integer>> repairs = new LinkedHashMap<>();
        if (evidence == null || evidence.isEmpty()) return Map.of();
        for (String sentence : factSentences(reply)) {
            List<Integer> original = refs(sentence, evidence.size());
            if (original.size() < 2) continue;
            List<Integer> supported = new ArrayList<>();
            for (int ref : original) {
                String single = CitationVerifier.repairCitations(sentence, Map.of(sentence, List.of(ref)), evidence);
                if (verify(true, single, evidence, List.of(), thresholds, graphChunkIds).unsupported().isEmpty()) supported.add(ref);
            }
            if (!supported.isEmpty() && supported.size() < original.size()) repairs.put(sentence, List.copyOf(supported));
        }
        return Map.copyOf(repairs);
    }

    private static boolean hasMedicalTerm(String text) {
        String normalized = normalize(text);
        return MEDICAL_TERMS.stream().anyMatch(term -> normalized.contains(normalize(term)));
    }

    /**
     * 商品行的放行判据：**与工具回执逐字段核对**，不是「句子里有没有医学词」。
     *
     * <p><b>为什么删掉前置排除词表。</b>原实现先按一张 40 词的排除表
     * （含 {@code 含有|成分|适用|每日|…}）把句子一票否决，再谈结构化核对。
     * 这张表与「商品事实 vs 医学结论」的分界无关——实测被它挡住的正是最正常的商品行：
     * {@code | 1 | 孕妇钙片 | 柠檬酸钙 500mg + 维生素 D3 400IU，孕期哺乳期适用 · SKU34 | 129.00 元 |}
     * 只因含「适用」二字即被否决，于是它只能退到医学证据链，而商品数据本就不在语料里
     * → 判无依据 → 删。
     *
     * <p><b>分界改由两件事守住</b>（都比词表准）：
     * <ol>
     *   <li>{@link #hasMedicalConclusion}——句中若含医学结论（服用剂量/相互作用/风险/疗效/适用人群），
     *       一律不走这条通道，回到医学链逐项核对。这是安全性边界，不放宽；</li>
     *   <li>逐字段核对——编号、规格文本、金额都必须能在**同一个候选块**里找到，
     *       且句中的规格与金额必须属于句中所引的那个 SKU。保证放行的只是「商品字段的复述」。</li>
     * </ol>
     *
     * <p><b>回执的两种形态都要认。</b>实时链路里 {@code rawData} 是结构化产物；
     * 持久化回放（评测快照、会话历史）里 {@code rawData} 会被裁剪，只剩工具自己写的
     * {@code output} 文本——生产 14 轮实测 {@code rawData} 全部为 null。只认前者会让
     * 同一条链路在两种读法下给出不同答案，那正是本项目批次 15「同一份 JSON 两条路
     * 一个能读一个读不了」的同一类缺陷。
     */
    public static boolean supportedProductRow(String sentence, List<ToolExecution> tools) {
        if (tools == null) return false;
        if (hasMedicalConclusion(normalize(sentence))) return false;
        Map<String, String> codes = new LinkedHashMap<>();
        Matcher code = PRODUCT_CODE.matcher(sentence);
        while (code.find()) {
            String kind = code.group(1).toLowerCase(Locale.ROOT);
            if (codes.putIfAbsent(kind, code.group(2)) != null) return false;
        }
        for (ToolExecution tool : tools) {
            if (!tool.isSuccess() || tool.isNoData() || !"product_search".equals(tool.getTool())) continue;
            for (ProductBlock block : productBlocks(tool)) {
                if (!block.covers(codes)) continue;
                if (!block.coversQuantities(sentence)) continue;
                if (!block.coversAmounts(sentence)) continue;
                if (block.nothingBeyond(sentence)) return true;
            }
        }
        return false;
    }

    /**
     * 工具回执的一个**候选块**——一个 SPU 及其全部规格行。
     *
     * <p>块内区分两种粒度：SPU 级字段（编号、商品名）对整块成立；SKU 级字段
     * （规格文本、价格）只对**那一行**成立。分开存是「同 SKU 绑定」的前提：
     * 「SKU38 30片25元」必须失败，因为 30 片属于 SKU39、25 元也属于 SKU39。
     */
    private record ProductBlock(String spuCode, String spuText, List<SkuLine> skus) {

        /**
         * 句中提到的每个 SPU/SKU 编号都必须在这一块里。
         * <p>
         * 句中一个编号都没提到时（「- 规格：柠檬酸钙 500mg …」这类续行）不按编号绑定，
         * 改由「整句 token 与金额都能在本块逐字段找到」兜底——编号是**消歧**手段，
         * 缺了它仍有纯度判据；把这类行一律拒掉，正是生产实测里商品描述被删的形态之一。
         */
        boolean covers(Map<String, String> codes) {
            if (codes.isEmpty()) return true;
            for (Map.Entry<String, String> entry : codes.entrySet()) {
                String wanted = entry.getKey().toUpperCase(Locale.ROOT) + entry.getValue();
                if ("SPU".equals(entry.getKey().toUpperCase(Locale.ROOT))) {
                    if (!wanted.equals(spuCode)) return false;
                } else if (skus.stream().noneMatch(line -> line.code().equals(wanted))) {
                    return false;
                }
            }
            return true;
        }

        /**
         * 句中的每个金额都必须等于**句中所引那个 SKU** 的价格。
         * <p>
         * 判据不是「这个数字在块里出现过」——那样 {@code SKU38 30片25元} 会因为 25 元
         * 属于同块的 SKU39 而被放行，用户看到的是「38 号规格卖 25 元」这个不存在的事实。
         */
        boolean coversAmounts(String sentence) {
            Set<String> wanted = skuCodesIn(sentence);
            Matcher money = MONEY.matcher(normalize(sentence));
            while (money.find()) {
                String amount = money.group(1) == null ? money.group(2) : money.group(1);
                BigDecimal value;
                try { value = new BigDecimal(amount).stripTrailingZeros(); }
                catch (NumberFormatException invalid) { return false; }
                boolean matched = skus.stream().filter(line -> wanted.isEmpty() || wanted.contains(line.code()))
                        .anyMatch(line -> line.price() != null && line.price().compareTo(value) == 0);
                if (!matched) return false;
            }
            return true;
        }

        /**
         * 规格数量必须与句中所引 SKU 绑定。
         * <p>
         * 判据分两类：出现在**任一 SKU 行文本**里的数量是「规格类」（60片/120片），
         * 必须属于句中所引的那个 SKU——{@code SKU38 30片25元} 里 30片 属于 SKU39，
         * 放行就等于告诉用户「38 号规格是 30 片」；不在任何 SKU 行文本里的是
         * 「成分含量类」（柠檬酸钙 500mg），它描述候选整体，块级存在即可。
         */
        boolean coversQuantities(String sentence) {
            Set<String> wanted = skuCodesIn(sentence);
            if (wanted.isEmpty()) return true;
            for (String quantity : quantities(sentence)) {
                boolean specLike = skus.stream().anyMatch(line -> line.text().contains(quantity));
                if (!specLike) continue;
                boolean owned = skus.stream().filter(line -> wanted.contains(line.code()))
                        .anyMatch(line -> line.text().contains(quantity));
                if (!owned) return false;
            }
            return true;
        }

        /**
         * 纯度判据：句中每个实词 token 都要能在这一块里找到，**标签词除外**。
         *
         * <p><b>为什么逐 token 而不是「剔除后必须为空」。</b>后者的失效形态是把结构字符
         * （表格竖线、行号、引用编号）也算「未声明内容」，于是一张完全正确的商品表格行
         * 永远过不了；放宽它又会放过夹带内容的句子。逐 token 把两件事分开：标点与编号是
         * **格式**（本来就该忽略），汉字与字母数字串是**内容**（必须对得上）。
         *
         * <p><b>标签词是第三条。</b>「规格：」「成分：」「价格：」「适用：」这类前缀是模型
         * 为可读性给字段起的名字，回执里没有也不该有——回执是「字段：值」的原始形态。
         * 把它们当内容核对，等于要求模型逐字复制工具的排版。
         */
        boolean nothingBeyond(String sentence) {
            String text = normalize(sentence);
            text = CITATION.matcher(text).replaceAll("");
            text = PRODUCT_CODE.matcher(text).replaceAll("");
            // 数量与金额是**值**，由 coversQuantities / coversAmounts 按 SKU 绑定核对；
            // 这里只核对剩下的**文字内容**。不先剔除它们，`60片39元` 会因为没有分隔符
            // 被当成一个整 token，而回执写的是「60片/瓶 39.00元」——一个字都对不上。
            text = QUANTITY.matcher(text).replaceAll("");
            text = MONEY.matcher(text).replaceAll("");
            // 列表序号与项目符号是**排版**，不是内容：`**1. 孕妇钙片**` 归一化后是
            // `1.孕妇钙片`，与回执里的 `孕妇钙片` 是同一个字段的两种排版。
            // 不剥掉它，整 token 对不上，一条完全正确的商品行会被拒。
            text = text.replaceFirst("^\\s*(?:\\d+[.)]|[-*+])", "");
            for (String token : text.split("[^\\p{IsHan}a-z0-9.]+")) {
                if (token.isBlank() || token.matches("[0-9.]+")) continue;
                if (FIELD_LABELS.contains(token)) continue;
                if (!contains(token)) return false;
            }
            return true;
        }

        /**
         * 块文本里找 token。
         * <p>
         * <b>两侧必须用同一把尺子。</b>句子一侧已经剔掉了数量与金额（见 nothingBeyond），
         * 块这一侧若还留着数字，{@code 软骨素与钙} 就永远对不上块里的
         * {@code 软骨素250毫克与钙200毫克}——两侧各剔一次，剩下的才是可比的「内容」。
         */
        private boolean contains(String token) {
            if (contentOnly(spuText).contains(token)) return true;
            return skus.stream().anyMatch(line -> contentOnly(line.text()).contains(token));
        }

        private static String contentOnly(String text) {
            return MONEY.matcher(QUANTITY.matcher(text).replaceAll("")).replaceAll("");
        }
    }

    /** 回执里的一条规格行：编号、规格文本、价格三者**绑在一起**，不能拆开互相借。 */
    private record SkuLine(String code, String text, BigDecimal price) { }

    private static Set<String> skuCodesIn(String sentence) {
        Set<String> codes = new LinkedHashSet<>();
        Matcher code = PRODUCT_CODE.matcher(sentence);
        while (code.find()) {
            if ("SKU".equalsIgnoreCase(code.group(1))) codes.add("SKU" + code.group(2));
        }
        return codes;
    }

    /**
     * 工具回执的候选块列表。
     *
     * <p>优先用结构化 {@code rawData}；它被裁剪时退回解析 {@code output}。
     * 两种形态产出同一份 {@link ProductBlock}，下游核对逻辑只有一套——
     * 结构化路把 {@code price} 分转元、文本路把「39.00 元」解析成同一个数值，
     * 于是同一条链路在两种读法下给出同一个答案。
     */
    private static List<ProductBlock> productBlocks(ToolExecution tool) {
        // 两种形态都要认，且产出同一份块：实时链路给 rawData（结构化、无歧义），
        // 持久化回放只剩 output（生产 14 轮实测 rawData 全部为 null）。
        // 结构化优先：它是工具亲手写的字段，文本是它的有损投影。
        List<ProductBlock> blocks = structuredBlocks(tool.getRawData());
        return blocks.isEmpty() ? textBlocks(tool.getOutput()) : blocks;
    }

    private static List<ProductBlock> structuredBlocks(Object rawData) {
        if (rawData == null) return List.of();
        Object decoded;
        try { decoded = MAPPER.convertValue(rawData, Object.class); }
        catch (RuntimeException ignored) { return List.of(); }
        if (!(decoded instanceof List<?> products)) return List.of();
        List<ProductBlock> blocks = new ArrayList<>();
        for (Object product : products) {
            if (!(product instanceof Map<?, ?> row)) continue;
            Map<?, ?> summary = row.get("summary") instanceof Map<?, ?> value ? value : Map.of();
            StringBuilder head = new StringBuilder();
            if (summary.get("id") != null) head.append(summary.get("id")).append(' ');
            if (summary.get("name") instanceof String name) head.append(name);
            List<SkuLine> lines = new ArrayList<>();
            if (row.get("skus") instanceof List<?> skus) {
                for (Object option : skus) {
                    if (!(option instanceof Map<?, ?> sku)) continue;
                    BigDecimal price = sku.get("price") instanceof Number number
                            ? new BigDecimal(number.toString()).movePointLeft(2) : null;
                    String spec = sku.get("specText") instanceof String value ? normalize(value) : "";
                    String text = price == null ? spec : spec + " " + price.toPlainString() + "元";
                    lines.add(new SkuLine(
                            sku.get("skuId") == null ? "" : "SKU" + sku.get("skuId"),
                            normalize(text), price));
                }
            }
            if (lines.isEmpty() && summary.get("id") != null) {
                lines.add(new SkuLine("", "", null));
            }
            blocks.add(new ProductBlock("SPU" + summary.get("id"), normalize(head.toString()), lines));
        }
        return blocks;
    }

    /**
     * 文本回执的解析——生产持久化视图里只剩这一种形态。
     *
     * <p>识别工具自己打印的两级结构：{@code - 候选[n] 编号 SPU25：…} 开一条候选，
     * 缩进的 {@code 规格：SKU34（…） 129.00 元 有货} 归入它。规格行按 {@code ；}
     * 拆开，每条各自绑定自己的编号与价格。解析不出来时返回空列表（不放行），
     * 不猜测——猜错的方向是放行编造内容。
     */
    private static List<ProductBlock> textBlocks(String output) {
        if (output == null || output.isBlank()) return List.of();
        List<ProductBlock> blocks = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.startsWith("- 候选[")) {
                blocks.add(new ProductBlock(spuCodeOf(line), normalize(line), new ArrayList<>()));
                continue;
            }
            if (blocks.isEmpty()) continue;
            String trimmed = line.trim();
            if (!trimmed.startsWith("规格：")) continue;
            for (String segment : trimmed.substring("规格：".length()).split("；")) {
                String code = "";
                Matcher sku = Pattern.compile("(?i)SKU\\s*(\\d+)").matcher(segment);
                if (sku.find()) code = "SKU" + sku.group(1);
                BigDecimal price = null;
                Matcher money = MONEY.matcher(normalize(segment));
                if (money.find()) {
                    String amount = money.group(1) == null ? money.group(2) : money.group(1);
                    try { price = new BigDecimal(amount); } catch (NumberFormatException ignored) { }
                }
                if (!code.isEmpty() || price != null) {
                    blocks.get(blocks.size() - 1).skus().add(new SkuLine(code, normalize(segment), price));
                }
            }
        }
        return blocks;
    }

    private static String spuCodeOf(String line) {
        Matcher spu = Pattern.compile("(?i)SPU\\s*(\\d+)").matcher(line);
        return spu.find() ? "SPU" + spu.group(1) : "";
    }
    /**
     * 句中是否含**医学结论**——这是商品行通道与医学链的分界，也是本类唯一放宽不了的地方。
     *
     * <p>商品事实（名称、编号、规格、价格、库存、**成分含量**）的来源是工具回执，
     * 可以逐字段核对；医学结论（服用剂量、用法、相互作用、风险、疗效、适用人群）
     * 的来源必须是知识切片，因为它的正确性不由「工具返回过」决定。
     *
     * <p><b>成分含量与服用剂量必须分开。</b>两者在字面上是同一形态（都是「数值 + 单位」）：
     * 「柠檬酸钙 500mg」是规格，「每日 500mg」是用法。区分的判据是**有没有服用语境**——
     * 前者没有，后者有（每日/每次/服用/剂量…）。早先的判据把两者一起当结论，
     * 于是每一条正常的商品规格行都被拦回医学链，而商品数据不在语料里 → 判无依据 → 删。
     *
     * <p><b>这个门只负责「有没有临床断言」。</b>数值本身对不对由
     * {@link #coversAmounts} 与 {@link #nothingBeyondReceipt} 逐字段核对——
     * 编造的「600mg」对不上回执，照样出不去。两道判据合起来才是完整的安全边界。
     */
    private static boolean hasMedicalConclusion(String normalizedText) {
        // 服用语境：有频次/用量词才是用法，不是规格。
        if (DOSING_CONTEXT.matcher(normalizedText).find()) return true;
        if (CLINICAL_POSTFIX.matcher(normalizedText).find()) return true;
        if (RELATION_DIRECTION.matcher(normalizedText).find()) return true;
        if (MONITORING.matcher(normalizedText).find()) return true;
        if (RISK_PRESENCE.matcher(normalizedText).find()) return true;
        if (INTERACTION_PRESENCE.matcher(normalizedText).find()) return true;
        if (SAFE_COMBINATION.matcher(normalizedText).find()) return true;
        if (ABSORPTION_COMPETITION.matcher(normalizedText).find()) return true;
        if (MEDICATION_CLASSIFICATION.matcher(normalizedText).find()) return true;
        if (SYMPTOM_WARNING.matcher(normalizedText).find()) return true;
        if (isVersionDecision(normalizedText)) return true;
        return normalizedText.matches(".*(?:禁用|不宜使用|拮抗|无风险|没有冲突|不存在相互作用|遵医嘱|咨询医师|咨询医生|适用人群|适合|有助|有助于|缓解|治疗|疗效|预防|补充|吸收).*");
    }

    /** 疑问句是向用户提问，不是向用户断言事实；带具体数值/编号的疑问句仍在确认事实，照常核验。 */
    private static boolean isQuestionToUser(String sentence) {
        String visible = MedicalText.visible(sentence).trim();
        if (!visible.endsWith("？") && !visible.endsWith("?")) return false;
        return quantities(sentence).isEmpty() && !PRODUCT_CODE.matcher(sentence).find();
    }

    /**
     * 哪些断言在轮级证据门之外还要**再看一次引用切片**。
     *
     * <p>轮级判定回答「这一轮证据够不够回答这个问题」，不回答「这一句的结论是不是
     * 落在它引用的那一片上」。对低后果陈述（适宜人群、一般膳食建议），后者由主体/
     * 数量/人群/极性逐字段核对负责，重排分不参与——短句与口语化转述的重排分天然
     * 偏低，拿它二次判死会删掉原文逐字支撑的结论。
     *
     * <p>高后果断言保留逐片判定：<b>带具体数值的剂量</b>、相互作用、风险、禁忌、
     * 药物分类、监测要求。这些内容写错一位就会误导用户，宁可多拦。判据与
     * {@link #hasMedicalConclusion} 同源，不另起一张词表。
     */
    private static boolean requiresChunkLevelGate(String normalizedSentence) {
        return requiresAbsoluteChunkGate(normalizedSentence)
                || !quantities(normalizedSentence).isEmpty()
                || MedicalQuantities.hasUnparsedQuantity(normalizedSentence);
    }

    /**
     * 哪些断言必须由**引用切片自己跨过绝对门槛**。
     *
     * <p>这一层只放高后果结论：相互作用、风险、禁忌、安全警示、版本裁定。
     * 它们写错一位就会误导用户，不能因为「同轮另有强片」就放行——强片支撑的是别的事实，
     * 不是这一句。纯剂量/用法句先由轮级证据门确认本轮够用，再按相对档核对是否明显弱于
     * 本轮最强依据；纯膳食建议、适宜人群、饮水提醒等低后果陈述不在其中。
     */
    private static boolean requiresAbsoluteChunkGate(String normalizedSentence) {
        return CLINICAL_POSTFIX.matcher(normalizedSentence).find()
                || RELATION_DIRECTION.matcher(normalizedSentence).find()
                || MONITORING.matcher(normalizedSentence).find()
                || RISK_PRESENCE.matcher(normalizedSentence).find()
                || INTERACTION_PRESENCE.matcher(normalizedSentence).find()
                || SAFE_COMBINATION.matcher(normalizedSentence).find()
                || ABSORPTION_COMPETITION.matcher(normalizedSentence).find()
                || MEDICATION_CLASSIFICATION.matcher(normalizedSentence).find()
                || SYMPTOM_WARNING.matcher(normalizedSentence).find()
                || SAFETY_WARNING.matcher(normalizedSentence).find()
                || isVersionDecision(normalizedSentence)
                || normalizedSentence.matches(".*(?:禁用|不宜使用|拮抗).*");
    }

    /**
     * 一片引用证据是否可用。绝对档要求它自己达到配置阈值；相对档只要求它不明显弱于
     * 本轮最强依据（{@link #RELATIVE_CHUNK_SCORE_FLOOR}）。没有分数时沿用证据门的
     * fail-open：缺失指标不等于不相关。
     */
    private static boolean chunkUsable(int ref, List<DocumentChunk> evidence, EvidenceGate.Thresholds thresholds,
                                       java.util.Set<String> graphChunkIds, boolean absolute) {
        if (ref < 1 || ref > evidence.size()) return false;
        DocumentChunk chunk = evidence.get(ref - 1);
        if (chunk == null) return false;
        String key = chunk.getChunkId() == null || chunk.getChunkId().isBlank()
                ? chunk.getDocId() + "#" + chunk.getChunkIndex() : chunk.getChunkId();
        java.util.Set<String> graph = graphChunkIds == null ? java.util.Set.of()
                : graphChunkIds.contains(key) ? java.util.Set.of(key) : java.util.Set.of();
        if (absolute) {
            return EvidenceGate.evaluate(List.of(chunk), thresholds, graph).isSufficient();
        }
        if (chunk.getScore() == null) return true;
        Double topScore = evidence.stream().filter(java.util.Objects::nonNull)
                .map(DocumentChunk::getScore).filter(java.util.Objects::nonNull)
                .max(Double::compareTo).orElse(null);
        if (topScore == null || topScore <= 0) return true;
        // 图谱依据的相关性由结构给出，文本相似度天然偏低；已有图谱豁免时不再按分数卡。
        if (!graph.isEmpty()) return true;
        return chunk.getScore() >= topScore * RELATIVE_CHUNK_SCORE_FLOOR;
    }

    private static String invalidReason(String sentence, List<Integer> refs,
                                        List<DocumentChunk> evidence,
                                        EvidenceGate.Thresholds thresholds, java.util.Set<String> graphChunkIds,
                                        boolean roundSufficient,
                                        Set<String> verifiedSubjects, Set<String> verifiedPopulation, String ownerContext) {
        String normalizedSentence = normalize(sentence);
        // 售后/物流/订单政策句里的「七天无理由」「72 小时发货」不是医学剂量，不能
        // 进入医学严格门：它没有剂量、关系或风险结论可核验，出处由普通引用校验负责。
        // G-15 的真实售后回答引用了逐字支撑的售后切片，却因为「七天」被判
        // 「剂量/时间数量写法不明确」，整句被剔除、正确引用被记成 WRONG_TARGET。
        // 判据由 isMedicalFact 内的 NON_CLINICAL_POLICY 收窄为「政策动作词 + 时间量
        // 且无临床信号」；真正的医学结论仍走下面的完整核对。
        if (!isMedicalFact(sentence) && NON_CLINICAL_POLICY.matcher(normalizedSentence).find()) return null;
        if (MedicalQuantities.hasUnparsedQuantity(normalizedSentence)) return "剂量/时间数量写法不明确，不能按空断言放行";

        // 证据门分两层，回答两个不同的问题：
        //   1) 本轮证据够不够回答这个问题——由 EvidenceGate 对**整轮**判一次；
        //   2) 这一句的高后果结论有没有落在它引用的那一片上——只对带数值的剂量、
        //      相互作用、风险、禁忌这类断言再判一次。
        //
        // 低后果陈述（适宜人群、一般膳食建议）不再按引用切片的重排分二次判死：
        // 轮级已经判过「这一轮证据够」，逐片再判一次是同一事实两处各算；重排分对
        // 短句/口语化转述天然偏低，实测 0.14~0.19 的切片原文逐字支撑结论
        //（2026-10-18 alice 便秘会话）。高后果断言保留逐片判定：数值或关系写错
        // 一位就会误导用户，不能只靠轮级；「同轮有强片、句子却引了弱片」的既有
        // 契约由这一层守住。
        if (!roundSufficient) {
            // 轮级不足时，只有引用切片自己达到绝对门槛或属于图谱依据才放行；
            // 否则仍按轮级拒绝。这样既不整篇误杀，也不给弱片开口子。
            boolean citedStrong = refs.stream().anyMatch(ref -> chunkUsable(ref, evidence, thresholds, graphChunkIds, true));
            if (!citedStrong) return "本轮证据低于统一证据门，不能支撑医学结论";
        }
        if (thresholds != null && requiresChunkLevelGate(normalizedSentence)) {
            // 高后果断言按绝对门槛逐片复核：写错一位就会误导用户，不能只靠轮级。
            // 纯剂量/用法句走相对判据：它已经有主体/数值/人群/角色/约束的逐字段核验，
            // 重排分只再回答「这片是不是明显弱于本轮最强依据」，不再用同一绝对阈值二次判死。
            boolean allUsable = refs.stream().allMatch(ref -> chunkUsable(ref, evidence, thresholds, graphChunkIds,
                    requiresAbsoluteChunkGate(normalizedSentence)));
            if (!allUsable) {
                return "引用证据低于统一证据门，不能支撑医学结论";
            }
        }
        if (ABSENCE.matcher(normalizedSentence).find() && !LIMITED_SEARCH.matcher(normalizedSentence).find()) {
            return "医学严格模式不允许用局部切片证明整份资料不存在该内容";
        }
        Set<String> claimTerms = new LinkedHashSet<>();
        for (String term : MEDICAL_TERMS) {
            if (normalizedSentence.contains(normalize(term))) {
                claimTerms.add(term);
            }
        }

        List<DocumentChunk> citedEvidence = refs.stream().map(ref -> evidence.get(ref - 1)).toList();
        // 「本品」的归属可以来自**这句引用的切片**：当且仅当所有被引用切片都指向同一个商品时，
        // 那个商品就是句子里的「本品」。这与「句子必须自己点名商品」不是同一条判据：
        // 后者防的是同一轮出现多份说明书时归属歧义（KG-02/KG-11），而这里的前提正是
        // 多份证据中**只有一份**被这句引用——归属没有第二种可能。
        Set<String> citedOwners = citedEvidence.stream().map(chunk -> chunk == null ? null : productOwner(chunk.getTitle()))
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String citedOwner = citedOwners.size() == 1 ? citedOwners.iterator().next() : null;
        boolean graphClaim = normalizedSentence.contains("图谱");
        if (graphClaim) {
            boolean graphEvidence = citedEvidence.stream().anyMatch(MedicalEvidenceVerifier::isGraphEvidence);
            if (!graphEvidence) {
                return "图谱/关系声明没有图谱证据切片";
            }
        }
        // 版本裁定会改变“以谁为准”的结论方向，只有引用切片原文给出替代授权才算被支撑。
        if (isVersionDecision(normalizedSentence)
                && citedEvidence.stream().noneMatch(chunk -> chunk != null && hasVersionAuthorization(normalize(sourceText(chunk))))) {
            return "版本优先裁定缺少引用原文的替代授权";
        }

        Set<String> mentionedTerms = claimTerms.stream()
                .filter(term -> normalizedSentence.contains(normalize(term)))
                .filter(term -> quantities(sentence).isEmpty() || !Set.of("孕期", "妊娠期", "哺乳期", "儿童").contains(term))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> failures = new LinkedHashSet<>();
        // 「本品」指代的归属：当**来源展示框架本身被引用编号化**时，改由引用切片的元数据给出。
        //
        // 编号化把句首的《文档名》换成了 [n]（前端据此渲染可点击角标），但「第四章写明：」
        // 这个框架动词还在——它证明这句话原本声明了来源。原先的判据要求商品名出现在
        // 句子文本里，于是两个都正确的设计合起来把「引用对了、主体也对」的句子判成
        // 「本品没有声明来源」：实测 KG-04 的正文因此被整句删除，最终 finalFacts=0。
        //
        // <b>判据必须收窄到「框架被编号化」这一形态，不能放宽成「引用的切片有归属」。</b>
        // 后者会让「本品所含钙可影响…[1]」这种<b>根本没声明来源</b>的句子一并放行——
        // 它引用的切片确实是一份说明书，但句子没有说「本品」就是那一份，
        // 同一段回答里出现第二份说明书时归属立刻歧义（KG-02/KG-11 两个用例守的正是这条）。
        // 差别在于：框架动词是句子**自己**给出的来源声明，编号只是它的书写形式。
        boolean numberedSourceFrame = NUMBERED_SOURCE_FRAME.matcher(normalizedSentence).find();
        // 章节框架与编号框架是同一件事的两种写法，合并成一个判据：
        // 句子自己声明了「来源是某一份文档的某一节」，归属由引用切片提供
        boolean sourceFrame = numberedSourceFrame
                || SECTION_SOURCE_FRAME.matcher(normalizedSentence).find();
        for (DocumentChunk chunk : citedEvidence) {
            if (chunk == null) { failures.add("引用切片不存在"); continue; }
            if (graphClaim && !isGraphEvidence(chunk)) {
                failures.add("引用切片不是图谱来源"); continue;
            }
            String normalizedSource = normalize(sourceText(chunk));
            String clinicalSentence = normalizedSentence;
            Matcher reportedSource = Pattern.compile("^《?([^：:《》]+?说明书)》?(?:里|中)?(?:也)?(?:写明|指出|说明|提到|记载)[：:]").matcher(normalizedSentence);
            if (reportedSource.find()) {
                String declaredOwner = productOwner(reportedSource.group(1));
                if (declaredOwner == null || !declaredOwner.equals(productOwner(chunk.getTitle()))) {
                    failures.add("来源展示框架与引用商品不一致"); continue;
                }
                // 已匹配的来源名称保留身份约束；临床参与组只取其后的正文，不能把标题成分变成服药对象。
                // “说明书写明：所含钙”中的“所含”仍指已核对的商品，拆框架后须保留此语法绑定。
                clinicalSentence = normalizedSentence.substring(reportedSource.end()).replaceFirst("^所含", "本品所含");
            } else if (sourceFrame) {
                // 编号框架是同一件事的另一种写法：`《X 说明书》第四章写明：…` 在
                // CitationVerifier.numberTitles 之后会变成 `[1]第四章写明：…`——
                // 标题被换成编号，来源身份**没有消失，只是换了载体**。
                //
                // 此前这个形态漏了：`reportedSource` 要求句首是《…说明书》，匹配不到，
                // 于是落到下面「本品必须有归属声明」那条，而归属声明恰好刚被编号替换掉——
                // 判据在追一个自己刚擦掉的证据。实测代价（c091b3b9 的 KG-04）：
                // 模型把 K2 与华法林的拮抗关系答得完全正确、引用也对，整段却被剔除，
                // 最终交付一句「无法根据本次检查判断维生素 K2与华法林的具体关系」。
                //
                // 身份改由**引用切片自身的 title** 提供（编号 [n] 指的就是第 n 片证据），
                // 与 reportedSource 分支取的是同一份信息，只是来源从句子挪到了引用表。
                String owner = productOwner(chunk.getTitle());
                if (owner == null || owner.isBlank()) {
                    failures.add("编号来源框架无法定位引用商品"); continue;
                }
                // 保留 ownerContext：后续句子省略主体时要能接住这个身份
                clinicalSentence = normalizedSentence;
            }
            if (PRODUCT_PRONOUN.matcher(normalizedSentence).find() && isMedicalFact(sentence)) {
                String owner = productOwner(chunk.getTitle());
                if (owner == null || !normalizedSentence.contains(owner) && !owner.equals(ownerContext)
                        && !sourceFrame
                        && !(PRODUCT_COMPOSITION_PRONOUN.matcher(normalizedSentence).find() && owner.equals(citedOwner))) {
                    failures.add("本品临床事实没有声明引用来源所属商品"); continue;
                }
            }
            if (!supportsClassification(normalizedSentence, sourceText(chunk))) {
                failures.add("具体药物分类未被同一来源明确声明"); continue;
            }
            if (SAFE_COMBINATION.matcher(normalizedSentence).find()
                    && (!SAFE_COMBINATION.matcher(normalizedSource).find()
                    || normalizedSource.matches(".*(不安全|不能安全|不可以安全).*"))) {
                failures.add("安全合用结论未被引用切片支持"); continue;
            }
            if (mentionedTerms.stream().anyMatch(term -> !normalizedSource.contains(normalize(term)))) {
                failures.add("句中实体与引用切片不一致"); continue;
            }
            if (RELATIONS.values().stream().anyMatch(relation -> relation.matcher(normalizedSentence).find()
                    && !relation.matcher(normalizedSource).find())) {
                failures.add("关系类别与引用切片不一致"); continue;
            }
            String doseReason = MedicalDoseAssertions.rejection(clinicalSentence, sourceText(chunk), chunk.getTitle(), MEDICAL_TERMS, verifiedSubjects, verifiedPopulation);
            if (isMedicalFact(sentence) && doseReason != null) {
                failures.add(doseReason); continue;
            }
            if (isMedicalFact(sentence) && !supportsRelationPolarity(clinicalSentence, sourceText(chunk), verifiedSubjects)) {
                failures.add("关系对象或极性与引用切片不一致"); continue;
            }
            String symptomReason = symptomWarningRejection(clinicalSentence, normalizedSource);
            if (symptomReason != null) {
                failures.add(symptomReason); continue;
            }
            // 已结构化的临床方向由对象与极性核验；其余谓语仍须原文支持，不能借同段另一关系。
            String remainder = RELATION_DIRECTION.matcher(normalizedSentence).replaceAll("");
            remainder = CLINICAL_POSTFIX.matcher(remainder).replaceAll("");
            boolean unsupportedDirection = false;
            for (String polarity : List.of("不会", "不增加", "不影响", "无风险", "没有风险", "可以放心", "安全同服")) {
                if (remainder.contains(polarity) && !normalizedSource.contains(polarity)) unsupportedDirection = true;
            }
            for (String direction : List.of("增加", "降低", "减少", "促进", "抑制", "减弱", "增强", "拮抗")) {
                if (remainder.contains(direction) && !normalizedSource.contains(direction)) unsupportedDirection = true;
            }
            if (unsupportedDirection) { failures.add("未结构化关系方向与引用切片不一致"); continue; }
            return null;
        }
        return "同一引用切片未支撑：" + String.join("；", failures);
    }

    /** 版本语境下的“以哪份资料为准”裁定：只认显式版本标识或新旧比较语。 */
    private static boolean isVersionDecision(String normalizedSentence) {
        boolean mentionsVersion = normalizedSentence.contains("版本") || VERSION_MENTION.matcher(normalizedSentence).find();
        return mentionsVersion && VERSION_DECISION.matcher(normalizedSentence).find();
    }

    /** 引用切片是否给出替代授权；“不能替代药物治疗”是否定表述，不构成版本授权。 */
    private static boolean hasVersionAuthorization(String normalizedSource) {
        return VERSION_SUPERSEDE.matcher(VERSION_NEGATED_SUPERSEDE.matcher(normalizedSource).replaceAll("")).find();
    }

    /** 症状列表与行动都必须落在同一引用切片里，不能借同段别的“出血”语句补齐“及时就医”绑定。 */
    private static String symptomWarningRejection(String sentence, String normalizedSource) {
        Matcher warning = SYMPTOM_WARNING.matcher(CITATION.matcher(sentence).replaceAll(""));
        if (!warning.find()) return null;
        for (String symptom : warning.group("symptoms").split("[、，,;；和与及]")) {
            String part = symptom.trim();
            if (!part.isEmpty() && !normalizedSource.contains(part)) {
                return "警示症状未被同一引用切片支持";
            }
        }
        String action = warning.group("action").trim();
        if (!action.isEmpty() && !normalizedSource.contains(action)) {
            return "处置建议未被同一引用切片支持";
        }
        return null;
    }

    private static String productOwner(String title) {
        String value = normalize(title);
        var suffix = Pattern.compile("(?:产品)?(?:说明书|使用与贮存指南|规格与食用指南)$").matcher(value);
        return suffix.find() && suffix.start() > 0 ? value.substring(0, suffix.start()) : null;
    }

    private record Classification(Set<String> entities, String category, boolean negative) { }

    private static List<Classification> classifications(String text) {
        List<Classification> result = new ArrayList<>();
        for (String clause : normalize(text).split("[。！？!?；;，,\\n]+")) {
            Matcher matcher = MEDICATION_CLASSIFICATION.matcher(clause);
            while (matcher.find()) {
                Set<String> entities = MedicalDoseAssertions.entities(clause.substring(0, matcher.start()).replaceFirst("(?:通常|一般)$", ""), MEDICAL_TERMS);
                result.add(new Classification(entities, matcher.group(2).startsWith("非") ? "非处方" : "处方",
                        !Set.of("是", "属于", "为").contains(matcher.group(1))));
            }
        }
        return result;
    }

    private static boolean supportsClassification(String claim, String source) {
        List<Classification> facts = classifications(source);
        return classifications(claim).stream().allMatch(item -> !item.entities().isEmpty()
                && facts.stream().anyMatch(fact -> fact.entities().containsAll(item.entities())
                && item.category().equals(fact.category()) && item.negative() == fact.negative()));
    }

    /** 极性绑定到风险对象或吸收关系，避免从同段另一种关系借用方向词。 */
    private static boolean supportsRelationPolarity(String sentence, String source, Set<String> verifiedSubjects) {
        List<RelationFact> facts = relationFacts(source, Set.of());
        return relationFacts(sentence, verifiedSubjects).stream().allMatch(claim -> !claim.entities().isEmpty()
                && (!claim.requiresPair() || claim.entities().size() >= 2)
                && facts.stream().anyMatch(fact -> supportsParticipants(claim.entities(), fact.entities(), sentence, source)
                && (!fact.requiresPair() || !claim.polarities().keySet().stream().anyMatch(key -> key.startsWith("监测:")) || claim.entities().size() >= 2)
                && claim.polarities().entrySet().stream().allMatch(entry -> {
                    Set<String> supported = fact.polarities().getOrDefault(entry.getKey(), Set.of());
                    if (entry.getKey().startsWith("风险:") && fact.polarities().getOrDefault("风险:风险", Set.of()).contains("不存在")
                            && !entry.getValue().equals(Set.of("不存在"))) return false;
                    return supported.containsAll(entry.getValue());
                })));
    }

    private record RelationFact(Set<String> entities, Map<String, Set<String>> polarities, boolean requiresPair) { }

    private static boolean supportsParticipants(Set<String> claim, Set<String> fact, String sentence, String source) {
        if (fact.containsAll(claim)) return true;
        String text = normalize(sentence);
        if (!claim.contains("鱼油") || !text.matches("(?s).*鱼油(?:中的|的)epa(?:与|和|及)dha.*")) return false;
        String sourceText = normalize(source);
        Matcher title = Pattern.compile("《([^》]+)》").matcher(sourceText);
        if (!title.find()) return false;
        String owner = productOwner(title.group(1));
        if (owner == null || !owner.contains("鱼油") || owner.contains("磷虾")
                || !sourceText.matches("(?s).*本品所含epa(?:与|和|及)dha.*")) return false;
        Set<String> bound = new LinkedHashSet<>(claim);
        bound.remove("鱼油");
        return fact.containsAll(bound) && fact.stream().anyMatch(entity -> entity.endsWith("鱼油") && owner.contains(entity));
    }

    private static List<RelationFact> relationFacts(String text, Set<String> verifiedSubjects) {
        List<RelationFact> result = new ArrayList<>();
        Set<String> inherited = verifiedSubjects;
        boolean previousHadRelation = false;
        boolean enumerating = false;
        Set<String> titleOwner = Set.of();
        String visible = MedicalText.visible(text);
        int previousEnd = 0;
        for (String raw : RELATION_CLAUSE_BOUNDARY.split(visible)) {
            int start = visible.indexOf(raw, previousEnd);
            String separator = visible.substring(previousEnd, start);
            if (separator.matches("(?s).*[。！？!?；;\\n].*") || raw.matches("^(?:但是|但|然而|而).*")) enumerating = false;
            previousEnd = start + raw.length();
            String clause = normalize(raw);
            Set<String> entities = MedicalDoseAssertions.entities(clause, MEDICAL_TERMS);
            if (enumerating || clause.matches("^(?:与|和|及).*" )
                    || clause.matches(".*(?:与|和|及).*(?:合用|同服).*")) {
                Set<String> explicit = new LinkedHashSet<>(entities);
                String objects = clause.replaceFirst("^(?:与|和|及)", "").split("(?:合用|同服)", 2)[0];
                for (String item : objects.split("、|与|和|及")) {
                    String name = item.split("等", 2)[0];
                    if (!name.matches("(?:上述|这些|该|这|此)(?:药物|药品|产品|商品).*")) {
                        explicit.addAll(MedicalDoseAssertions.entities(name, MEDICAL_TERMS));
                    }
                }
                entities = Set.copyOf(explicit);
            }
            if (clause.contains("说明书") || clause.contains("指南")) {
                Matcher title = Pattern.compile("《([^》]+)》").matcher(clause);
                String declaredTitle = title.find() ? title.group(1) : clause;
                String owner = productOwner(declaredTitle);
                titleOwner = owner == null ? Set.of() : MedicalDoseAssertions.entities(owner, MEDICAL_TERMS);
            }
            if (clause.matches("^(?:\\d+(?:\\.\\d+)*[.、]?)?本品所含.*") && !titleOwner.isEmpty()) {
                Set<String> joined = new LinkedHashSet<>(titleOwner); joined.addAll(entities); entities = Set.copyOf(joined);
            }
            if (clause.matches(".*(?:的)?(?:化学结构|结构)(?:与|和).*(?:相似|类似).*")) {
                inherited = MedicalDoseAssertions.entities(clause.split("(?:的)?(?:化学结构|结构)", 2)[0], MEDICAL_TERMS);
                previousHadRelation = false;
                continue;
            }
            if (entities.isEmpty()) entities = inherited;
            else if (enumerating || (clause.matches("^(?:与|和|及|本品|该产品|同时).*" ) && (!previousHadRelation || entities.size() == 1))
                    || clause.matches("^(?:可能|可|会|不会|不|未|没有)?(?:" + RELATION_ACTIONS + ").*")
                    || clause.matches("^(?:正在)?服用.*者.*") && inherited.containsAll(entities)) {
                Set<String> joined = new LinkedHashSet<>(inherited); joined.addAll(entities); entities = joined;
            }
            if (!entities.isEmpty()) inherited = entities;
            Map<String, Set<String>> polarities = relationPolarities(clause);
            if (clause.matches("^(?:与|和|及).*" ) && polarities.isEmpty() && !clause.contains("合用")) enumerating = true;
            if (clause.contains("合用") || !polarities.isEmpty()) enumerating = false;
            boolean requiresPair = INTERACTION_PRESENCE.matcher(clause).find()
                    || hasMedicalEffect(clause) && !RELATION_DIRECTION.matcher(clause).find()
                    || clause.contains("合用") && MONITORING.matcher(clause).find();
            if (!polarities.isEmpty()) result.add(new RelationFact(entities, polarities, requiresPair));
            previousHadRelation = !polarities.isEmpty();
        }
        return result;
    }

    private static Map<String, Set<String>> relationPolarities(String text) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        Matcher clinical = CLINICAL_POSTFIX.matcher(text);
        while (clinical.find()) {
            String key = clinicalObject(clinical.group(1));
            String polarity = Set.of("提高", "增加", "增强", "上升").contains(clinical.group(3)) ? "增加" : "降低";
            if (clinical.group(2) != null) polarity = "不" + polarity;
            result.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(polarity);
        }
        Matcher direction = RELATION_DIRECTION.matcher(text);
        while (direction.find()) {
            boolean risk = "风险".equals(direction.group(4));
            String key = risk ? "风险:" + direction.group(3) + "风险" : clinicalObject(direction.group(4));
            String action = direction.group(2);
            String polarity = switch (action) {
                case "增加", "提高", "升高", "促进", "增强" -> "增加";
                case "降低", "减少", "抑制", "减弱", "阻碍" -> "降低";
                default -> "影响";
            };
            if (direction.group(1) != null) polarity = "不" + polarity;
            Set<String> polarities = result.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
            polarities.add(polarity);
            if (direction.group(1) == null) {
                if (risk && "增加".equals(polarity)) polarities.add("存在");
                if (!risk) polarities.add("影响");
                result.computeIfAbsent("影响", ignored -> new LinkedHashSet<>()).add("存在");
                result.computeIfAbsent("相互作用", ignored -> new LinkedHashSet<>()).add("存在");
            }
        }
        Matcher presence = RISK_PRESENCE.matcher(text);
        while (presence.find()) {
            String polarity = Set.of("有", "存在").contains(presence.group(1)) ? "存在" : "不存在";
            result.computeIfAbsent("风险:" + presence.group(2), ignored -> new LinkedHashSet<>()).add(polarity);
        }
        Matcher interactions = INTERACTION_PRESENCE.matcher(text);
        while (interactions.find()) {
            String polarity = Set.of("有", "存在").contains(interactions.group(1)) ? "存在" : "不存在";
            result.computeIfAbsent("相互作用", ignored -> new LinkedHashSet<>()).add(polarity);
        }
        Matcher effects = EFFECT_PRESENCE.matcher(text);
        while (effects.find()) {
            String polarity = effects.group(1) == null ? "存在" : "不存在";
            result.computeIfAbsent("影响", ignored -> new LinkedHashSet<>()).add(polarity);
            result.computeIfAbsent("相互作用", ignored -> new LinkedHashSet<>()).add(polarity);
        }
        Matcher poorAbsorption = POOR_ABSORPTION.matcher(text);
        while (poorAbsorption.find()) {
            result.computeIfAbsent("吸收", ignored -> new LinkedHashSet<>()).add("降低");
        }
        Matcher competition = ABSORPTION_COMPETITION.matcher(text);
        while (competition.find()) {
            String polarity = competition.group().matches("^(?:不存在|没有|无).*") ? "不存在" : "存在";
            result.computeIfAbsent("吸收竞争", ignored -> new LinkedHashSet<>()).add(polarity);
            if ("存在".equals(polarity)) result.computeIfAbsent("相互作用", ignored -> new LinkedHashSet<>()).add("存在");
        }
        Matcher monitoring = MONITORING.matcher(text);
        while (monitoring.find()) {
            String metric = monitoring.group("metric").startsWith("凝血") ? "凝血" : monitoring.group("metric");
            String polarity = monitoring.group("negativeBefore") != null || monitoring.group("negativeAfter") != null ? "不要求" : "要求";
            result.computeIfAbsent("监测:" + metric, ignored -> new LinkedHashSet<>()).add(polarity);
        }
        return result;
    }

    private static String clinicalObject(String object) {
        if (object.endsWith("风险")) return "风险:" + object;
        return switch (object) {
            case "吸收", "生物利用度" -> "吸收";
            case "抗凝作用", "抗凝效果" -> "抗凝";
            case "药效", "疗效" -> "疗效";
            default -> object;
        };
    }

    private static boolean isGraphEvidence(DocumentChunk chunk) {
        return chunk != null && (Boolean.TRUE.equals(chunk.getGraphBacked()) || DocumentChunk.SOURCE_GRAPH.equals(chunk.getSource()));
    }

    private static Set<String> quantities(String text) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = QUANTITY.matcher(normalize(text));
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private static List<Integer> refs(String sentence, int evidenceCount) {
        Set<Integer> result = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(MedicalText.references(sentence));
        while (matcher.find()) {
            int ref;
            try {
                ref = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // 模型引用编号是不可信输入；超出整数范围属于无效引用，不是审核故障。
                continue;
            }
            if (ref >= 1 && ref <= evidenceCount) {
                result.add(ref);
            }
        }
        return List.copyOf(result);
    }

    private static String sourceText(DocumentChunk chunk) {
        if (chunk == null) {
            return "";
        }
        return String.join("\n", safe(chunk.getTitle()), safe(chunk.getPosition()), safe(chunk.getContent()));
    }

    private static Map<String, Pattern> relationPatterns() {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        patterns.put("BLEEDING", Pattern.compile("出血|抗血小板|凝血"));
        patterns.put("ABSORPTION", Pattern.compile("吸收|生物利用度|间隔|错开"));
        patterns.put("INTERACTION", Pattern.compile("相互作用|同服|合用"));
        patterns.put("DOSAGE", Pattern.compile("每日|每次|剂量|用量|摄入量|上限|超过|粒|片|毫克|微克|IU"));
        patterns.put("CONTRAINDICATION", Pattern.compile("禁用|不宜|咨询医师|咨询医生|遵医嘱|适用人群"));
        return Map.copyOf(patterns);
    }

    static String normalize(String value) {
        // 药名D3后接“400IU”或“三岁”时保留数词边界，不能把药名编号拼进剂量/年龄。
        String visible = MedicalText.visible(safe(value));
        String text = Pattern.compile("\\s+").matcher(visible).replaceAll(match ->
                match.start() > 0 && match.end() < visible.length()
                        && Character.isDigit(visible.charAt(match.start() - 1)) && Character.isDigit(visible.charAt(match.end())) ? " " : "")
                .toLowerCase(Locale.ROOT)
                .replace("μg", "微克").replace("µg", "微克").replace("mcg", "微克")
                .replace("mg", "毫克").replace("国际单位", "iu");
        // 仅规范已有词表中成对声明的药类简称；共享后缀是原文明确的并列语法，不增加药名或关系。
        text = MEDICATION_CLASS_COORDINATION.matcher(text).replaceAll(match ->
                Matcher.quoteReplacement(match.group(1) + "药" + match.group(2) + match.group(3) + "药"));
        for (var alias : MEDICATION_CLASS_ALIASES.entrySet()) text = text.replace(alias.getKey(), alias.getValue());
        return MedicalQuantities.normalize(text);
    }

    private static Map<String, String> medicationClassAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String term : MEDICAL_TERMS) if (term.endsWith("药物")) {
            String shortName = term.substring(0, term.length() - 1);
            if (MEDICAL_TERMS.contains(shortName)) aliases.put(term, shortName);
        }
        return Map.copyOf(aliases);
    }

    private static Pattern medicationClassCoordination() {
        String stems = MEDICATION_CLASS_ALIASES.values().stream()
                .map(term -> Pattern.quote(term.substring(0, term.length() - 1)))
                .collect(java.util.stream.Collectors.joining("|"));
        return stems.isEmpty() ? Pattern.compile("(?!)") : Pattern.compile("(" + stems + ")(或|和|及|、)(" + stems + ")药(?:物)?");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
