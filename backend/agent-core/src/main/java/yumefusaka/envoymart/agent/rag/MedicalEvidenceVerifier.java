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
    private static final Pattern RISK_PRESENCE = Pattern.compile(
            "(没有|无|不存在|存在|有)([^，,。；;！？!?]{0,12}?风险)");
    private static final Pattern SAFE_COMBINATION = Pattern.compile(
            "安全(?:同服|合用|一起吃)|(?:同服|合用|一起吃)(?:是|属于)?安全(?:的)?|没有冲突");
    /**
     * 症状警示句：给出可核对症状列表与行动建议，如“出现异常出血、皮肤瘀斑等表现应及时就医”。
     * 通用提醒（“如有疑问请咨询医师”）没有症状中介语，不进入事实句分母与核验。
     */
    private static final Pattern SYMPTOM_WARNING = Pattern.compile(
            "(?:出现|发生|若有|如有|若出现|如出现|一旦出现)(?<symptoms>[^，,。；;！？!?\\n]{2,60}?)(?:等)?(?:表现|症状|不适|反应|情况)(?:时)?[，,]?(?<action>(?:应|需|须|要|请|建议|务必)[^，,。；;！？!?\\n]{1,30})");
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
            if (isScopedGraphAbsence(sentence, currentGraphScope)) continue;
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
            boolean selfContainedDose = quantities(sentence).size() == 1 && hasMedicalTerm(sentence);
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
                Set<String> scopedSubjects = MONITORING.matcher(normalize(claim)).find() && !headingSubjects.isEmpty()
                        ? headingSubjects : antecedentSubjects;
                reason = invalidReason(claim, refs, safeEvidence, thresholds, graphChunkIds, scopedSubjects, antecedentPopulation, ownerContext);
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

    public static boolean isMedicalQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = normalize(question);
        return MEDICAL_TERMS.stream().anyMatch(term -> normalized.contains(normalize(term)))
                || normalized.matches(".*(?:推荐摄入|最高摄入|可耐受|摄入上限).*" )
                || normalized.matches(".*(相互作用|同服|服药|用药|服用|剂量|药品|药物|抗生素|补充剂|保健品|头疼|头痛|肚子不舒服|腹痛|睡不好|失眠|发烧|发热|咳嗽|腹泻|便秘|过敏|恶心|呕吐|吃什么药|能吃什么|一起吃|一起用|合用|吃了以后).*" )
                || Pattern.compile("(?:每天|每日|每次).{0,6}(?:几|多少)(?:粒|片|颗|袋|毫克|微克|克)"
                        + "|(?:多久|多长时间).{0,6}(?:吃|服用)|(?:吃|服用).{0,4}(?:几|多少)(?:粒|片|颗|袋|毫克|微克|克)"
                        + "|^(?:那)?(?:几|多少)(?:毫克|微克|克)[?？]?$" ).matcher(normalized).find();
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

    /** 只豁免成功检索的同一规格字段，不从展示文本或实体词汇共现推断商品或医学事实。 */
    private static boolean supportedProductRow(String sentence, List<ToolExecution> tools) {
        String text = normalize(sentence);
        if (tools == null || text.matches(".*(?:每日|每天|每次|用量|剂量|服用|服药|用药|同服|合用|一起吃|一起用|可以吃|建议|相互|风险|安全|禁用|不宜|禁忌|吸收|拮抗|治疗|疗效|功效|预防|缓解|摄入|有助|适合|适用|增加|降低|减少|促进|抑制|增强|影响|改善|含有|成分).*")) {
            return false;
        }
        Map<String, String> codes = new LinkedHashMap<>();
        Matcher code = PRODUCT_CODE.matcher(sentence);
        while (code.find()) {
            String kind = code.group(1).toLowerCase(Locale.ROOT);
            if (codes.putIfAbsent(kind, code.group(2)) != null) return false;
        }
        Set<String> packageQuantities = quantities(PRODUCT_CODE.matcher(sentence).replaceAll(""));
        if (codes.isEmpty() || packageQuantities.isEmpty()) return false;
        for (ToolExecution tool : tools) {
            if (!tool.isSuccess() || tool.isNoData() || !"product_search".equals(tool.getTool())
                    || tool.getRawData() == null) continue;
            Object decoded;
            try { decoded = MAPPER.convertValue(tool.getRawData(), Object.class); }
            catch (RuntimeException ignored) { continue; }
            if (!(decoded instanceof List<?> products)) continue;
            for (Object product : products) {
                if (!(product instanceof Map<?, ?> row) || !(row.get("summary") instanceof Map<?, ?> summary)
                        || !(row.get("skus") instanceof List<?> skus)) continue;
                if (codes.containsKey("spu") && !codes.get("spu").equals(String.valueOf(summary.get("id")))) continue;
                for (Object option : skus) {
                    if (!(option instanceof Map<?, ?> sku) || !(sku.get("specText") instanceof String spec)
                            || !(sku.get("price") instanceof Number price)) continue;
                    if (codes.containsKey("sku") && !codes.get("sku").equals(String.valueOf(sku.get("skuId")))) continue;
                    if (!quantities(spec).containsAll(packageQuantities)) continue;
                    BigDecimal yuan = new BigDecimal(price.toString()).movePointLeft(2);
                    Matcher money = MONEY.matcher(text);
                    boolean amountMatches = true;
                    while (money.find()) {
                        String amount = money.group(1) == null ? money.group(2) : money.group(1);
                        if (yuan.compareTo(new BigDecimal(amount)) != 0) amountMatches = false;
                    }
                    String remaining = normalize(PRODUCT_CODE.matcher(sentence).replaceAll(""));
                    if (summary.get("name") instanceof String name) remaining = remaining.replace(normalize(name), "");
                    remaining = remaining.replace(normalize(spec), "");
                    remaining = MONEY.matcher(remaining).replaceAll("");
                    remaining = QUANTITY.matcher(remaining).replaceAll("");
                    remaining = remaining.replaceAll("规格|单价|价格|包装|编号|商品|候选|瓶|盒|袋|装", "")
                            .replaceAll("[\\p{P}\\p{S}\\s]", "");
                    if (amountMatches && remaining.isEmpty()) return true;
                }
            }
        }
        return false;
    }

    private static String invalidReason(String sentence, List<Integer> refs,
                                        List<DocumentChunk> evidence,
                                        EvidenceGate.Thresholds thresholds,
                                        java.util.Set<String> graphChunkIds, Set<String> verifiedSubjects, Set<String> verifiedPopulation, String ownerContext) {
        String normalizedSentence = normalize(sentence);
        if (MedicalQuantities.hasUnparsedQuantity(normalizedSentence)) return "剂量/时间数量写法不明确，不能按空断言放行";

        if (thresholds != null) {
            boolean allUsable = refs.stream().allMatch(ref -> {
                DocumentChunk chunk = evidence.get(ref - 1);
                if (chunk == null) return false;
                String key = chunk.getChunkId() == null || chunk.getChunkId().isBlank()
                        ? chunk.getDocId() + "#" + chunk.getChunkIndex() : chunk.getChunkId();
                return EvidenceGate.evaluate(List.of(chunk), thresholds,
                        graphChunkIds == null ? java.util.Set.of() : graphChunkIds.contains(key) ? java.util.Set.of(key) : java.util.Set.of()).isSufficient();
            });
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
            }
            if (PRODUCT_PRONOUN.matcher(normalizedSentence).find() && isMedicalFact(sentence)) {
                String owner = productOwner(chunk.getTitle());
                if (owner == null || !normalizedSentence.contains(owner) && !owner.equals(ownerContext)) {
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
            String doseReason = MedicalDoseAssertions.rejection(clinicalSentence, sourceText(chunk), MEDICAL_TERMS, verifiedSubjects, verifiedPopulation);
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
