package yumefusaka.envoymart.agent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/** 同片也不能借另一实体或另一剂量角色的数值；不做单位换算或剂量推理。 */
final class MedicalDoseAssertions {
    private static final Pattern ROLE = Pattern.compile("每粒|每片|每桶|每罐|每瓶|每盒|每袋|规格|含量|含有|含|可耐受最高|最高摄入量|最高|上限|不超过|超过|推荐摄入量|推荐摄入|推荐|建议|适宜摄入量|间隔|相隔|错开|每日|每天|每次|服用|服药|用药|摄入|补充|剂量|用量|取");
    private static final Set<String> CONTEXT_TERMS = Set.of("孕期", "妊娠期", "哺乳期", "儿童", "肾功能", "肝功能", "出血性疾病", "保健食品", "膳食补充剂");
    private static final Pattern POPULATION = Pattern.compile("\\d+(?:-\\d+)?岁(?:以上|以下)?|备孕期|备孕|孕早期|孕期|妊娠期|孕妇|哺乳期|儿童|婴幼儿|老年|成年|成人|男性|女性|男|女");
    private static final Pattern POPULATION_PREFIX = Pattern.compile("(?:" + POPULATION.pattern().replace("|男|女", "") + ")(?:人群|人|者)?");
    private static final Pattern POPULATION_FRAME = Pattern.compile("^(?:对于|对)(?:健康)?" + POPULATION_PREFIX.pattern() + "(?:而言|来说)?$");
    private static final Pattern CLAUSE_BOUNDARY = Pattern.compile("[。！？!?；;\\n，,]+");
    private static final Pattern VERSION_FRAME = Pattern.compile("^(?:另)?(?:一|这|该)(?:种|份|个)?(?:规格|版本|说明|版)(?:是|为)[：:,，]*");
    private static final Pattern QUANTITY_FRAME = Pattern.compile("^(?:折合|(?:另一份|这份|该份)资料给出的是同一数值的另一种写法[—–:：]*)(?=\\d)");
    private static final Pattern PAIR_PRONOUN = Pattern.compile("两者|二者");
    private static final Pattern RELATION_SCOPE = Pattern.compile("合用|同服|相互作用|相互影响|吸收竞争|间隔|相隔|错开");
    private static final Pattern CLINICAL_ENDPOINT = Pattern.compile("吸收|生物利用度|药效|疗效|抗凝作用|抗凝效果");
    private static final Pattern TOPIC_FRAME = Pattern.compile("^(?:现在|接下来)?(?:讨论|说回|关于|至于|针对).*");
    private static final Pattern START_TIME = Pattern.compile("从(孕前|孕后)?(\\d+(?:\\.\\d+)?(?:-\\d+(?:\\.\\d+)?)?(?:小时|分钟|天|周|个月|年))(?:开始|起)");
    private static final Pattern SUBJECT_HEAD = Pattern.compile("^([\\p{IsHan}a-z][\\p{IsHan}a-z0-9]{0,30}?)(?=\\s*(?:"
            + ROLE.pattern() + "|" + POPULATION.pattern()
            + "|同服|合用|应|需|至少|不少于|监测|吸收|生物利用度|药效|疗效|(?:不会|不能|不|可能|会)?(?:增加|提高|升高|降低|减少|促进|抑制|影响|增强|阻碍)"
            + "|(?:没有|无|不存在|存在|有)(?:明确|任何|已知)?相互作用|(?:不会|不能|不|可能|会)?相互影响|吸收竞争"
            + "|\\d+(?:毫克|微克|克|iu|粒|片|丸|袋|小时|分钟)))");
    private record Assertion(Set<String> entities, Set<String> population, String role, String quantity, String constraint) { }
    private record Mention(int offset, String entity) { }
    record Scope(Set<String> subjects, Set<String> population) { }

    private MedicalDoseAssertions() { }

    static boolean supports(String claim, String source, List<String> terms) {
        return supports(claim, source, terms, Set.of());
    }

    static boolean supports(String claim, String source, List<String> terms, Set<String> verifiedSubjects) {
        return supports(claim, source, terms, verifiedSubjects, Set.of());
    }

    static boolean supports(String claim, String source, List<String> terms, Set<String> verifiedSubjects, Set<String> verifiedPopulation) {
        return rejection(claim, source, terms, verifiedSubjects, verifiedPopulation) == null;
    }

    static String rejection(String claim, String source, List<String> terms, Set<String> verifiedSubjects, Set<String> verifiedPopulation) {
        List<Assertion> facts = assertions(source, terms, Set.of(), Set.of());
        // “两者/二者”是原文常见的成对回指：句内解析不到完整关系对时，允许由引用切片中
        // 数量与约束逐项一致的间隔断言来接住；数值、角色、人群、先后约束一条都不放宽。
        boolean pairPronoun = PAIR_PRONOUN.matcher(MedicalEvidenceVerifier.normalize(claim)).find();
        for (Assertion item : assertions(claim, terms, verifiedSubjects, verifiedPopulation)) {
            if ("OTHER".equals(item.role())) return "剂量角色不明确";
            boolean unresolvedPairInterval = pairPronoun && "INTERVAL".equals(item.role()) && item.entities().size() < 2;
            if (!unresolvedPairInterval
                    && (item.entities().isEmpty() || "INTERVAL".equals(item.role()) && item.entities().size() < 2)) return "数量主体不完整";
            List<Assertion> matching = facts.stream().filter(fact -> item.quantity().equals(fact.quantity())).toList();
            if (unresolvedPairInterval) {
                // 代词复述只能落到引用切片里真正解析出成对实体的间隔断言上，单实体数值不算。
                matching = matching.stream().filter(fact -> fact.entities().size() >= 2).toList();
                if (matching.isEmpty()) return "间隔断言未被引用切片支持";
            }
            if (matching.isEmpty()) return "数值未被引用切片支持";
            matching = matching.stream().filter(fact -> fact.entities().containsAll(item.entities())).toList();
            if (matching.isEmpty()) return "数量主体与引用切片不一致";
            matching = matching.stream().filter(fact -> supportsPopulation(item.population(), fact.population())).toList();
            if (matching.isEmpty()) return "人口范围与引用切片不一致";
            matching = matching.stream().filter(fact -> item.role().equals(fact.role())).toList();
            if (matching.isEmpty()) return "剂量角色与引用切片不一致";
            if (matching.stream().noneMatch(fact -> item.constraint().equals(fact.constraint()))) return "时间下界或先后约束与引用切片不一致";
        }
        return null;
    }

    static Set<String> subjects(String claim, List<String> terms, Set<String> verifiedSubjects) {
        Set<String> subjects = new LinkedHashSet<>();
        assertions(claim, terms, verifiedSubjects, Set.of()).forEach(assertion -> subjects.addAll(assertion.entities()));
        return Set.copyOf(subjects);
    }

    static Scope scope(String claim, List<String> terms, Set<String> verifiedSubjects, Set<String> verifiedPopulation) {
        List<Assertion> facts = assertions(claim, terms, verifiedSubjects, verifiedPopulation);
        return facts.isEmpty() ? new Scope(Set.of(), Set.of()) : new Scope(facts.getLast().entities(), facts.getLast().population());
    }

    private static List<Assertion> assertions(String text, List<String> terms, Set<String> verifiedSubjects, Set<String> verifiedPopulation) {
        List<Assertion> result = new ArrayList<>();
        Set<String> inherited = verifiedSubjects;
        Set<String> titleSubjects = verifiedSubjects;
        Set<String> inheritedPopulation = verifiedPopulation;
        Set<String> framedPopulation = Set.of();
        Set<String> relationParticipants = verifiedSubjects.size() >= 2 ? verifiedSubjects : Set.of();
        Set<String> sentenceParticipants = Set.of();
        String inheritedRole = "OTHER";
        String visible = MedicalText.visible(text);
        int clauseEnd = 0;
        for (String raw : CLAUSE_BOUNDARY.split(visible)) {
            int clauseStart = visible.indexOf(raw, clauseEnd);
            String separator = visible.substring(clauseEnd, clauseStart);
            if (separator.matches("(?s).*[。！？!?；;\\n].*")) sentenceParticipants = Set.of();
            if (separator.matches("(?s).*[。！？!?；;\\n].*")) framedPopulation = Set.of();
            if (separator.matches("(?s).*\\n\\s*\\n.*")) relationParticipants = Set.of();
            clauseEnd = clauseStart + raw.length();
            String clause = MedicalEvidenceVerifier.normalize(raw);
            List<Mention> mentions = mentions(clause, terms);
            Set<String> named = entities(clause, terms);
            if (TOPIC_FRAME.matcher(clause).matches()) {
                relationParticipants = Set.of();
            } else if (named.size() >= 2 && RELATION_SCOPE.matcher(clause).find()) {
                relationParticipants = Set.copyOf(named);
                sentenceParticipants = relationParticipants;
            } else if (!named.isEmpty() && !(named.size() == 1 && relationParticipants.containsAll(named)
                    && CLINICAL_ENDPOINT.matcher(clause).find())) {
                relationParticipants = Set.of();
                sentenceParticipants = Set.of();
            }
            if (clause.contains("说明书") || clause.contains("指南")) {
                Set<String> names = new LinkedHashSet<>(); mentions.forEach(mention -> names.add(mention.entity()));
                if (!names.isEmpty()) titleSubjects = Set.copyOf(names);
            }
            var quantity = MedicalEvidenceVerifier.QUANTITY.matcher(clause);
            var roles = new ArrayList<Mention>();
            var roleMatcher = ROLE.matcher(clause);
            while (roleMatcher.find()) roles.add(new Mention(roleMatcher.start(), role(roleMatcher.group())));
            int previousQuantityEnd = 0;
            boolean hasQuantity = false;
            while (quantity.find()) {
                hasQuantity = true;
                Set<String> subject = new LinkedHashSet<>();
                for (Mention mention : mentions) if (mention.offset() >= previousQuantityEnd && mention.offset() <= quantity.start()) subject.add(mention.entity());
                boolean explicitSubject = !subject.isEmpty();
                if (subject.isEmpty()) subject = inherited;
                if (subject.isEmpty() && !mentions.isEmpty()) subject = Set.of(mentions.getFirst().entity());
                Set<String> population = population(clause.substring(previousQuantityEnd, quantity.start()));
                if (population.isEmpty() && (result.isEmpty() || subject.equals(inherited))) population = framedPopulation;
                if (population.isEmpty() && result.isEmpty() && subject.equals(verifiedSubjects)) population = verifiedPopulation;
                String kind = inheritedRole;
                List<Mention> preceding = new ArrayList<>();
                for (Mention role : roles) {
                    if (role.offset() >= previousQuantityEnd && role.offset() <= quantity.start()) preceding.add(role);
                }
                if (!preceding.isEmpty()) {
                    List<Mention> specific = preceding.stream().filter(role -> !Set.of("DOSE", "FREQUENCY").contains(role.entity())).toList();
                    kind = (specific.isEmpty() ? preceding : specific).getLast().entity();
                } else {
                    for (Mention role : roles) {
                        if (role.offset() >= quantity.end()) { kind = role.entity(); break; }
                    }
                }
                if ("FREQUENCY".equals(kind)) {
                    kind = "OTHER".equals(inheritedRole) ? "DOSE" : inheritedRole;
                    if (quantity.group().matches(".*(?:粒|片|丸|袋)$")) kind = "DOSE";
                }
                if ("CONTENT".equals(kind) && preceding.stream().anyMatch(role ->
                        clause.substring(role.offset()).matches("^(?:每粒|每片|每桶|每罐|每瓶|每盒|每袋|规格).*"))
                        && !explicitSubject
                        && !titleSubjects.isEmpty()) {
                    subject = titleSubjects;
                }
                if (quantity.group().matches(".*(?:粒|片|丸|袋)$") && "RECOMMENDED".equals(kind)) kind = "DOSE";
                // 每瓶/盒等包装数量以容器为语法主体，不继承上一项成分，也不能当服用剂量。
                if (quantity.group().matches(".*(?:粒|片|丸|袋)$") && preceding.stream().anyMatch(role ->
                        clause.substring(role.offset()).matches("^(?:每瓶|每盒|每桶|每罐).*"))) {
                    kind = "PACKAGE";
                    subject = Set.of("包装容器");
                }
                String constraint = "";
                var startTime = START_TIME.matcher(clause);
                boolean starting = false;
                while (startTime.find()) if (startTime.start(2) == quantity.start()) {
                    kind = "START";
                    constraint = startTime.group(1) == null ? "UNSPECIFIED" : startTime.group(1);
                    starting = true;
                }
                if (!starting && quantity.group().matches(".*(?:小时|分钟)$") && (clause.matches(".*(?:间隔|相隔|错开).*" )
                        || clause.substring(quantity.end()).matches("^后再.*"))) {
                    kind = "INTERVAL";
                    subject = new LinkedHashSet<>();
                    for (Mention mention : mentions) subject.add(mention.entity());
                    if (clause.contains("本品")) subject.addAll(titleSubjects);
                    if (subject.isEmpty()) subject = inherited;
                    if (mentions.isEmpty() && !sentenceParticipants.isEmpty()) subject = sentenceParticipants;
                    // “两者”回指完整关系，不回指最后一次提到的单个药物终点；一般剂量不借此扩大主体。
                    if (PAIR_PRONOUN.matcher(clause).find()) subject = relationParticipants;
                    constraint = clause.substring(0, quantity.start()).matches(".*(?:至少|不少于)(?:(?:间隔|相隔|错开))?$")
                            || clause.substring(quantity.end()).matches("^(?:以上|及以上).*" ) ? "LOWER" : "EXACT";
                    if (clause.substring(quantity.end()).matches("^后再.*")) {
                        String before = clause.substring(0, quantity.start());
                        String after = clause.substring(quantity.end()).replaceFirst("^后再(?:服用|食用|用|吃)", "");
                        Set<String> afterSubjects = after.startsWith("本品") ? titleSubjects : entities(after, terms);
                        constraint = before.contains("恰好") ? "EXACT" : "LOWER";
                        constraint += ":" + entities(before, terms) + ">" + afterSubjects;
                    }
                }
                if (population.isEmpty() && kind.equals(inheritedRole) && subject.equals(inherited)) {
                    population = inheritedPopulation;
                } else if (population.equals(Set.of("男性")) || population.equals(Set.of("女性"))) {
                    if (inheritedPopulation.contains("成人") && subject.equals(inherited) && kind.equals(inheritedRole)) {
                        Set<String> joined = new LinkedHashSet<>(population); joined.add("成人"); population = Set.copyOf(joined);
                    }
                }
                result.add(new Assertion(Set.copyOf(subject), population, kind, quantity.group(), constraint));
                inherited = Set.copyOf(subject);
                inheritedRole = kind;
                inheritedPopulation = population;
                previousQuantityEnd = quantity.end();
            }
            if (!hasQuantity) {
                if (POPULATION_FRAME.matcher(clause).matches()) framedPopulation = population(clause);
                Set<String> names = entities(clause, terms);
                if (!names.isEmpty()) inherited = Set.copyOf(names);
                if (!roles.isEmpty()) {
                    String kind = roles.getLast().entity();
                    inheritedRole = "FREQUENCY".equals(kind) ? "OTHER".equals(inheritedRole) ? "DOSE" : inheritedRole : kind;
                }
            }
        }
        return result;
    }

    static Set<String> entities(String text, List<String> terms) {
        Set<String> result = new LinkedHashSet<>();
        String normalized = MedicalEvidenceVerifier.normalize(text);
        mentions(normalized, terms).forEach(mention -> result.add(mention.entity()));
        String subject = subjectText(normalized);
        var head = SUBJECT_HEAD.matcher(subject);
        // 谓语前的实体已由 mentions 按同一语法入口收集；不能把“没有/会/可能”补作裸主体。
        if (head.find()) return Set.copyOf(result);
        if (subject.matches("[\\p{IsHan}a-z][\\p{IsHan}a-z0-9]{0,30}") && population(subject).isEmpty()
                && !subject.matches("该|本|这|此|上述|两者|二者|双方")
                && !ROLE.matcher(subject).find() && !subject.matches(".*(?:相互作用|同服|合用|监测|增加|提高|升高|降低|减少|促进|抑制|减弱|增强|影响|阻碍|风险|吸收|生物利用度|资料|说明书|依据|指南|研究|内附|附赠|量勺|平勺).*")) {
            for (String name : subject.split("与|和|及")) if (!name.isEmpty() && terms.stream().noneMatch(term ->
                    name.contains(MedicalEvidenceVerifier.normalize(term)) && !("维生素".equals(term) && !name.equals(term)))) {
                result.add(canonical(name));
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> population(String text) {
        Set<String> result = new LinkedHashSet<>();
        var matcher = POPULATION.matcher(text);
        while (matcher.find()) result.add(switch (matcher.group()) {
            case "妊娠期", "孕妇" -> "孕期"; case "备孕期" -> "备孕"; case "成年" -> "成人";
            case "男" -> "男性"; case "女" -> "女性"; default -> matcher.group();
        });
        return Set.copyOf(result);
    }

    private static boolean supportsPopulation(Set<String> claim, Set<String> source) {
        if (claim.isEmpty()) return source.isEmpty();
        if (source.isEmpty()) return !claim.contains("孕期") && !claim.contains("哺乳期") && !claim.contains("儿童") && !claim.contains("婴幼儿");
        Set<String> claimGroup = new LinkedHashSet<>(claim); claimGroup.removeAll(Set.of("男性", "女性"));
        Set<String> sourceGroup = new LinkedHashSet<>(source); sourceGroup.removeAll(Set.of("男性", "女性"));
        Set<String> stages = Set.of("备孕", "孕早期", "孕期", "哺乳期");
        Set<String> claimStages = new LinkedHashSet<>(claimGroup); claimStages.retainAll(stages);
        Set<String> sourceStages = new LinkedHashSet<>(sourceGroup); sourceStages.retainAll(stages);
        if (!claimStages.isEmpty() || !sourceStages.isEmpty()) {
            if (claimStages.isEmpty() || claimStages.stream().noneMatch(stage -> sourceStages.contains(stage)
                    || "孕早期".equals(stage) && sourceStages.contains("孕期"))) return false;
            claimGroup.removeAll(stages); sourceGroup.removeAll(stages);
        }
        return sourceGroup.equals(claimGroup) && (!source.contains("男性") || claim.contains("男性"))
                && (!source.contains("女性") || claim.contains("女性"));
    }

    private static List<Mention> mentions(String text, List<String> terms) {
        List<String> candidates = terms.stream().filter(term -> !CONTEXT_TERMS.contains(term))
                .map(MedicalEvidenceVerifier::normalize).sorted(Comparator.comparingInt(String::length).reversed()).toList();
        List<Mention> result = new ArrayList<>();
        for (int offset = 0; offset < text.length(); offset++) {
            for (String term : candidates) {
                if (text.startsWith(term, offset)) {
                    result.add(new Mention(offset, canonical(term)));
                    offset += term.length() - 1;
                    break;
                }
            }
        }
        {
            String subjectText = subjectText(text);
            var subject = SUBJECT_HEAD.matcher(subjectText);
            boolean matched = subject.find();
            if (!matched) {
                // 引导框架（“平台知识库的原文依据是：…”）会让行首锚定的主语头在冒号处断掉，
                // 词表外的裸主体（如“抗生素”）补不进来，间隔断言随即被判“数量主体不完整”。
                // 整段匹配失败时对最后一个冒号后的正文再解析一次；尾部同样解析不出就维持原判。
                int cut = Math.max(subjectText.lastIndexOf('：'), subjectText.lastIndexOf(':'));
                if (cut >= 0 && cut < subjectText.length() - 1) {
                    subjectText = subjectText.substring(cut + 1);
                    subject = SUBJECT_HEAD.matcher(subjectText);
                    matched = subject.find();
                }
            }
            if (matched && !POPULATION.matcher(subject.group(1)).matches() && !ROLE.matcher(subject.group(1)).find()
                    && !subjectText.matches("^(?:没有|不存在|无|存在|有|不会|不能|不|会|可能|可)?(?:相互作用|相互影响|影响).*" )
                    && !subjectText.matches("^(?:与|和|及)?(?:上述|这些|该|这|此)(?:药物|药品|产品|商品|补充剂).*" )
                    && !subject.group(1).matches("(?:但|而|也|同时|则|并|此外|另外|具体|每|可耐受|一次|单次|首次|约|大约|至少|通常|一般|正常|健康|长期|本品|该产品).*" )
                    && !subject.group(1).matches("(?:该|这|此|上述)(?:产品|商品|罐装|瓶装|药品|补充剂).*" )
                    && !subject.group(1).matches("该|本|这|此|上述|两者|二者|双方")
                    && !(subject.group(1).matches("[一二三四五六七八九十两0-9]+(?:平勺|勺|粒|片|丸|袋|瓶|盒|桶)(?:约|约为|重)?")
                    && (MedicalQuantities.QUANTITY.matcher(subject.group(1)).find() || MedicalQuantities.hasUnparsedQuantity(subject.group(1))))
                    && !subject.group(1).matches(".*(?:资料|说明书|依据|证据|指南|研究).*")) {
                for (String part : subject.group(1).split("与|和|及|、")) {
                    String name = part.replaceFirst("(?:可能|不会|不能|会|不|可|也)$", "");
                    if (name.isEmpty()) continue;
                    boolean known = candidates.stream().anyMatch(term -> name.contains(term)
                            && !("维生素".equals(term) && !name.equals(term)));
                    if (!known) result.add(new Mention(text.indexOf(part), canonical(name)));
                }
            } else if (result.isEmpty()) {
                var title = Pattern.compile("^《?([\\p{IsHan}a-z0-9]{1,24}?)(?:产品)?说明书》?$").matcher(text);
                if (title.find()) result.add(new Mention(0, canonical(title.group(1))));
            }
        }
        result.sort(Comparator.comparingInt(Mention::offset));
        return result;
    }

    /** 同一语法入口用于内联数量与裸主体，不能只在某一分支剥离话语前缀。 */
    private static String subjectText(String text) {
        String body = text.replaceFirst("^\\d+(?:\\.\\d+)+(?=[\\p{IsHan}a-z])", "");
        return QUANTITY_FRAME.matcher(VERSION_FRAME.matcher(body).replaceFirst("")).replaceFirst("")
                .replaceFirst("^(?:结论|具体风险|风险提示)[：:]", "")
                .replaceFirst("^(?:(?:根据资料|根据说明书|同时|建议|推荐|(?:正在|在)?服用|摄入|补充|但|而|也|则|并|此外|另外|通常|一般|正常|健康|长期|首次|一次|单次|(?:在)?(?:合用|同服)(?:期间|时)|"
                        + POPULATION_PREFIX.pattern() + ")[：:,，]*)+", "")
                .replaceFirst("^" + START_TIME.pattern(), "")
                .replaceFirst("^(?:应当|应|需要|需|必须|务必)(?=" + ROLE.pattern() + "|监测)", "");
    }

    private static String canonical(String term) {
        return switch (term) {
            case "维生素d3", "维生素d2" -> "维生素d";
            case "钙片", "钙剂" -> "钙"; case "铁剂" -> "铁";
            case "镁剂" -> "镁"; case "锌剂" -> "锌"; case "硒剂" -> "硒";
            default -> term;
        };
    }

    private static String role(String text) {
        return switch (text) {
            case "每粒", "每片", "每桶", "每罐", "每瓶", "每盒", "每袋", "规格", "含量", "含有", "含" -> "CONTENT";
            case "可耐受最高", "最高摄入量", "最高", "上限", "不超过", "超过" -> "UPPER";
            case "推荐摄入量", "推荐摄入", "推荐", "适宜摄入量" -> "RECOMMENDED";
            case "间隔", "相隔", "错开" -> "INTERVAL"; case "每日", "每天", "每次" -> "FREQUENCY"; default -> "DOSE";
        };
    }
}
