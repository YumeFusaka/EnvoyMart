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
    /**
     * Markdown 列表里的字段标签（「- 用法用量：…」「**适宜人群**：…」）是排版结构，
     * 不是剂量主体。若不在统一入口剥掉，{@code SUBJECT_HEAD} 会把「用法用量」当成
     * 裸主体，与引用原文的「成人每日 1 到 2 袋」对不上，正确引用会被判
     * 「同一引用切片未支撑」。只剥已知字段名，且必须紧跟冒号，避免误伤真实主体。
     */
    private static final Pattern STRUCTURE_FIELD_LABEL = Pattern.compile(
            "^(?:用法用量|用法与用量|用量|用法|服用方法|食用方法|怎么吃|怎么服用|怎么食用|吃法|喝法|推荐用量|每日用量|剂量|"
                    + "适宜人群|不适宜人群|适用人群|禁忌人群|注意事项|成分|配料|规格|贮藏|储存|保质期|"
                    + "产品名称|商品名称|品牌|价格|库存)[：:]\\s*");
    private static final Pattern START_TIME = Pattern.compile("从(孕前|孕后)?(\\d+(?:\\.\\d+)?(?:-\\d+(?:\\.\\d+)?)?(?:小时|分钟|天|周|个月|年))(?:开始|起)");
    private static final Pattern SUBJECT_HEAD = Pattern.compile("^([\\p{IsHan}a-z][\\p{IsHan}a-z0-9]{0,30}?)(?=\\s*(?:"
            + ROLE.pattern() + "|" + POPULATION.pattern()
            + "|同服|合用|应|需|至少|不少于|监测|吸收|生物利用度|药效|疗效|(?:不会|不能|不|可能|会)?(?:增加|提高|升高|降低|减少|促进|抑制|影响|增强|阻碍)"
            + "|(?:没有|无|不存在|存在|有)(?:明确|任何|已知)?相互作用|(?:不会|不能|不|可能|会)?相互影响|吸收竞争"
            + "|\\d+(?:毫克|微克|克|iu|粒|片|丸|袋|小时|分钟)))");
    /**
     * @param titleDerived 主体只来自引用切片的标题/文档元数据，而不是正文显式写出的实体。
     *                     产品级剂量（「成人每日 1 袋」）的 claim 常常省略主体，核对时
     *                     只能用「正文也没有具名主体」的事实来绑定；标题派生主体不能
     *                     被当成正文已经点名的成分，否则省略主体的产品剂量会被误判
     *                     「数量主体不完整」（2026-10-18 便秘会话）。
     */
    private record Assertion(Set<String> entities, Set<String> population, String role, String quantity, String constraint,
                             boolean titleDerived) {
        Assertion(Set<String> entities, Set<String> population, String role, String quantity, String constraint) {
            this(entities, population, role, quantity, constraint, false);
        }
    }
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

    /**
     * 引用切片带独立标题时，标题是**元数据**而不是正文。正文省略主体（「成人每日 1 片」）
     * 或使用「本品」时，主体由标题补足；正文自己写了主体时仍以正文为准。
     * 标题只在这一层参与绑定，不会被当成正文里的通配主体。
     */
    static boolean supports(String claim, String source, String sourceTitle, List<String> terms,
                            Set<String> verifiedSubjects, Set<String> verifiedPopulation) {
        return rejection(claim, source, sourceTitle, terms, verifiedSubjects, verifiedPopulation) == null;
    }

    static String rejection(String claim, String source, List<String> terms, Set<String> verifiedSubjects, Set<String> verifiedPopulation) {
        return rejection(claim, source, "", terms, verifiedSubjects, verifiedPopulation);
    }

    static String rejection(String claim, String source, String sourceTitle, List<String> terms,
                            Set<String> verifiedSubjects, Set<String> verifiedPopulation) {
        Set<String> titleSubjects = titleSubjects(sourceTitle, source, terms);
        List<Assertion> facts = assertions(sourceBody(source), terms, titleSubjects, Set.of(), true);
        // “两者/二者”是原文常见的成对回指：句内解析不到完整关系对时，允许由引用切片中
        // 数量与约束逐项一致的间隔断言来接住；数值、角色、人群、先后约束一条都不放宽。
        boolean pairPronoun = PAIR_PRONOUN.matcher(MedicalEvidenceVerifier.normalize(claim)).find();
        for (Assertion item : assertions(claim, terms, verifiedSubjects, verifiedPopulation)) {
            if ("OTHER".equals(item.role())) return "剂量角色不明确";
            boolean unresolvedPairInterval = pairPronoun && "INTERVAL".equals(item.role()) && item.entities().size() < 2;
            List<Assertion> matching = facts.stream().filter(fact -> item.quantity().equals(fact.quantity())).toList();
            // 产品级剂量（“成人每日 1～2 袋”“每袋 5 克”）句内没有成分名，主体由引用切片的
            // 标题/章节给出。此时不能因为「句内没有主体」就判不完整——引用切片本身也没有
            // 具名成分时，主体就是这份产品，数量/人群/角色/约束逐项对上即可。
            // 反过来，引用切片明确写了主体（“维生素 D 每日 2 粒”）而句子省略主体，仍然拒：
            // 省略的主语可能被读者安到别的产品上。
            boolean subjectlessProductDose = item.entities().isEmpty()
                    && !"INTERVAL".equals(item.role())
                    && matching.stream().anyMatch(fact -> fact.entities().isEmpty() || fact.titleDerived());
            // 人群可能只出现在原文的同一从句里（「成人推荐每日 1 袋」），而 claim 省略了人群；
            // 产品级剂量在数值/角色/约束逐项一致时，人群允许由引用原文补齐，不反过来要求
            // 句子必须复述人群。缺失人群不等于人群冲突。
            if (!unresolvedPairInterval && !subjectlessProductDose
                    && (item.entities().isEmpty() || "INTERVAL".equals(item.role()) && item.entities().size() < 2)) return "数量主体不完整";
            if (unresolvedPairInterval) {
                // 代词复述只能落到引用切片里真正解析出成对实体的间隔断言上，单实体数值不算。
                matching = matching.stream().filter(fact -> fact.entities().size() >= 2).toList();
                if (matching.isEmpty()) return "间隔断言未被引用切片支持";
            }
            if (matching.isEmpty()) return "数值未被引用切片支持";
            if (subjectlessProductDose) {
                matching = matching.stream().filter(fact -> fact.entities().isEmpty() || fact.titleDerived()).toList();
            } else {
                matching = matching.stream().filter(fact -> fact.entities().containsAll(item.entities())).toList();
            }
            if (matching.isEmpty()) return "数量主体与引用切片不一致";
            // 省略人群（claim 为空）允许由原文补齐；但 claim 明确写了人群时必须逐项核对，
            // 否则「备孕人群」会被泛化成任意人群，备孕禁忌被静默放行。
            if (!subjectlessProductDose || !item.population().isEmpty()) {
                matching = matching.stream().filter(fact -> supportsPopulation(item.population(), fact.population())).toList();
            }
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

    /**
     * 展示文本的前两行是标题与位置元数据，不是剂量正文。只剥离这两类
     * **有结构证据**的行：书名号标题行、带「说明书/指南/规范/规定」的标题行，
     * 以及紧随其后的 {@code > 章节 > 小节} 位置行。不再用「无句读短行」猜标题——
     * 那会把「成人每日 1 片」这类正文首句一起吃掉。
     */
    private static String sourceBody(String source) {
        String visible = MedicalText.visible(source);
        String[] lines = visible.split("\n", -1);
        int start = 0;
        while (start < lines.length && lines[start].isBlank()) start++;
        if (start < lines.length && (lines[start].matches("^《[^》]+》.*")
                || lines[start].matches(".*(?:说明书|指南|规范|规定).*"))) {
            start++;
        }
        while (start < lines.length && lines[start].matches("^\\s*>.*")) start++;
        return start >= lines.length ? visible : String.join("\n", java.util.Arrays.copyOfRange(lines, start, lines.length));
    }
    /**
     * 标题主体只用于「正文省略主体」与「本品」回指。优先使用调用方传入的
     * {@code chunk.title}；没有该元数据时（单测/历史调用）只认展示文本首行的
     * 明确标题（书名号或含「说明书/指南/规范/规定」），不把任意短行当标题。
     */
    private static Set<String> titleSubjects(String sourceTitle, String source, List<String> terms) {
        String title = sourceTitle;
        if (title == null || title.isBlank()) {
            String first = MedicalText.visible(source).split("\\n", 2)[0].trim();
            if (!first.matches("^《[^》]+》.*") && !first.matches(".*(?:说明书|指南|规范|规定).*")) return Set.of();
            title = first;
        }
        Set<String> result = new LinkedHashSet<>(entities(title, terms));
        return Set.copyOf(result);
    }
    private static List<Assertion> assertions(String text, List<String> terms, Set<String> verifiedSubjects, Set<String> verifiedPopulation) {
        return assertions(text, terms, verifiedSubjects, verifiedPopulation, false);
    }

    private static List<Assertion> assertions(String text, List<String> terms, Set<String> verifiedSubjects, Set<String> verifiedPopulation,
                                              boolean initialSubjectsAreTitleMetadata) {
        List<Assertion> result = new ArrayList<>();
        Set<String> inherited = verifiedSubjects;
        Set<String> titleSubjects = verifiedSubjects;
        boolean inheritedTitleDerived = initialSubjectsAreTitleMetadata && !verifiedSubjects.isEmpty();
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
                boolean subjectFromTitle = false;
                for (Mention mention : mentions) if (mention.offset() >= previousQuantityEnd && mention.offset() < quantity.start()) subject.add(mention.entity());
                boolean explicitSubject = !subject.isEmpty();
                // 剂量主体不能只看数量前的提及；但数量后的实体可能是服用对象（「抗生素 2 小时后再食用益生菌」），
                // 不能一概并入主体。只有 INTERVAL 分支需要按关系对重新解析，这里保持原有语义。
                if (subject.isEmpty()) {
                    subject = inherited;
                    subjectFromTitle = inheritedTitleDerived;
                }
                // 字段标签后的产品级剂量（「用法用量：成人每日 1 到 2 袋」）：句内只有人群、
                // 没有成分主体时，由引用切片的标题补足；正文显式写了成分时仍以正文为准。
                if (!titleSubjects.isEmpty() && (subject.isEmpty() || subject.stream().allMatch(name -> population(name).contains(name)))) {
                    subject = titleSubjects;
                    subjectFromTitle = true;
                }
                // 不能一概并入主体。只有 INTERVAL 分支需要按关系对重新解析，这里保持原有语义。
                if (subject.isEmpty() && !mentions.isEmpty()) {
                    subject = Set.of(mentions.getFirst().entity());
                    subjectFromTitle = false;
                }
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
                // 时间量（「1 周后」）是疗程/时间点，不是服用量：它不该参与剂量主体核对，
                // 否则「每日 1 袋开始，1 周后可酌情增加」会被判「数量主体与引用切片不一致」。
                boolean timeOnly = quantity.group().matches(".*(?:小时|分钟|天|周|个月|年)$");
                // 时间量只有两种身份：间隔/起始约束（必须核验），或「1 周后可酌情增加」这类
                // 疗程提示（不参与剂量主体核对）。用「同句是否有服用剂量角色」区分二者，
                // 不能整类跳过——否则「间隔 2 小时」会被放行。
                boolean dosingInClause = roles.stream().anyMatch(role ->
                        Set.of("DOSE", "CONTENT", "RECOMMENDED", "UPPER").contains(role.entity()));
                if (timeOnly && !"INTERVAL".equals(kind) && !"START".equals(kind) && !dosingInClause) {
                    inherited = Set.copyOf(subject);
                    inheritedRole = kind;
                    inheritedPopulation = population;
                    previousQuantityEnd = quantity.end();
                    continue;
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
                    subjectFromTitle = true;
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
                    subjectFromTitle = false;
                    if (clause.contains("本品") && !titleSubjects.isEmpty()) {
                        boolean onlyTitle = subject.isEmpty();
                        subject.addAll(titleSubjects);
                        subjectFromTitle = onlyTitle;
                    }
                    if (subject.isEmpty()) {
                        subject = inherited;
                        subjectFromTitle = inheritedTitleDerived;
                    }
                    if (mentions.isEmpty() && !sentenceParticipants.isEmpty()) {
                        subject = sentenceParticipants;
                        subjectFromTitle = false;
                    }
                    // 回指（“两者/二者”“这类药物/上述药物”）不是新主体：它的指代对象是同句前文
                    // 已经解析出的实体组。关系对能解析出来时优先用它（“两者”回指的是完整关系，
                    // 不是最后一次提到的单个药物终点）；解析不出来时退回 inherited——这条路径此前
                    // 没有出口，于是「所以两者服用时间应间隔 2 小时」在关系对恰好被噪声子句清空时
                    // 被判“数量主体不完整”。
                    boolean anaphora = PAIR_PRONOUN.matcher(clause).find()
                            || clause.matches(".*(?:上述|这些|这类|该类|该等|此等)(?:药物|药品|产品|商品|补充剂).*");
                    if (anaphora) {
                        // 「两者」可以跨句回指同段关系对：中文里「A 与 B 合用…。
                        // 两者应间隔 2 小时」是最自然的写法，要求同一句会把正确复述判死。
                        // 跨段/转题/出现新主体的保护已由 relationParticipants 的
                        // 空行、TOPIC_FRAME 与新实体分支负责——它为空时仍退回 inherited。
                        if (relationParticipants.isEmpty()) {
                            subject = inherited;
                            subjectFromTitle = inheritedTitleDerived;
                        } else {
                            subject = relationParticipants;
                            subjectFromTitle = false;
                        }
                    }
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
                result.add(new Assertion(Set.copyOf(subject), population, kind, quantity.group(), constraint, subjectFromTitle));
                inherited = Set.copyOf(subject);
                inheritedTitleDerived = subjectFromTitle;
                inheritedRole = kind;
                inheritedPopulation = population;
                previousQuantityEnd = quantity.end();
            }
            if (!hasQuantity) {
                if (POPULATION_FRAME.matcher(clause).matches()) framedPopulation = population(clause);
                Set<String> names = entities(clause, terms);
                if (!names.isEmpty()) {
                    inherited = Set.copyOf(names);
                    inheritedTitleDerived = false;
                }
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
                    && !subjectText.matches("^(?:与|和|及)?(?:上述|这些|该|这|此)(?:类|种|些|等)?(?:药物|药品|产品|商品|补充剂).*" )
                    // 以动词或连词开头的主体不是名词短语：「会形成难吸收的络合物」「导致药物吸收下降」
                    // 「所以两者服用时间应间隔 2 小时」会被 SUBJECT_HEAD 截成「会形成难」「导致药物」
                    // 「所以两者」，进主体集后把 relationParticipants 清空（named 非空且不满足关系参与
                    // 条件即重置），后面「两者」回指就再也拿不到主体——实测 KG-09 整句因此被判
                    // 「关系对象或极性与引用切片不一致」。判据是词性边界，不是词表长短：
                    // 名词短语不会以这些词开头，而它们在句中的位置恰恰是谓语。
                    // 「初次食用建议从每日 1 袋开始」里的「食用」是动词，不是产品主体。
                    // 这类服用动作词进入主体集后，产品级剂量会被判「数量主体不完整」；
                    // 只排除动作词本身，不排除「食用菌」这类真实名词短语（要求后接
                    // 建议/推荐/方法/量等结构词才判为动作框架）。
                    && !subject.group(1).matches("(?:初次)?(?:食用|服用|摄入|补充)(?=建议|推荐|方法|方式|量|剂量|时间|说明).*" )
                    && !subject.group(1).matches("(?:但|而|也|同时|则|并|此外|另外|具体|每|可耐受|一次|单次|首次|约|大约|至少|通常|一般|正常|健康|长期|本品|该产品|会|将|能|导致|所以|因此|从而|进而|于是|使|令|造成|引起|形成|故|可见|表明|意味着).*" )
                    && !subject.group(1).matches("(?:与|和|及)?(?:该|这|此|上述|这些)(?:类|种|些|等)?(?:产品|商品|罐装|瓶装|药品|药物|补充剂|成分).*" )
                    && !subject.group(1).matches("该|本|这|此|上述|两者|二者|双方")
                    && !(subject.group(1).matches("[一二三四五六七八九十两0-9]+(?:平勺|勺|粒|片|丸|袋|瓶|盒|桶)(?:约|约为|重)?")
                    && (MedicalQuantities.QUANTITY.matcher(subject.group(1)).find() || MedicalQuantities.hasUnparsedQuantity(subject.group(1))))
                    && !subject.group(1).matches(".*(?:资料|说明书|依据|证据|指南|研究).*")
                    // 「每日饮水量建议不少于 1500 毫升」里被推荐的对象是指标本身（饮水量/摄入量），
                    // 不是药名。把它当主体会让产品级的水量建议与引用原文的「膳食纤维」对不上，
                    // 正确复述被判「数量主体与引用切片不一致」。只排除明确的指标名词，
                    // 「维生素D摄入量」这类由词表实体开头的写法仍走 mentions 分支。
                    && !subject.group(1).matches("(?:每日|每天|每次|一日)?(?:饮水量|喝水量|摄入量|补充量|食用量|服用量|总摄入量|推荐量|水量)")) {
                for (String part : subject.group(1).split("与|和|及|、")) {
                    // 尾部情态助词（“抗生素要错开时间”解析出“抗生素要”）要剥掉；只剥“要”会
                    // 把“需要”削成“需”，所以多字符形式排在前面。
                    String name = part.replaceFirst("(?:需要|要|可能|不会|不能|会|不|可|也)$", "");
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
        // 行首 "- "/"* " 列表标记与 "**" 加粗会挡住 SUBJECT_HEAD 的 ^ 锚定（生成侧习惯用列表
        // 与加粗排版），句中又无冒号可回退，裸主体（如“抗生素”）就补不进来、整句被判
        // “数量主体不完整”——这是确定性复现的解析缺口，不是模型措辞问题。
        // 「3.2 中国居民…」这种章节号是排版结构，不是主体的一部分；只剥章节号本身，
        // 不能连后面的正文一起剥掉（旧写法要求章节号后紧跟汉字，遇到「中国居民」会整段失败）。
        String body = text.replaceFirst("^\\d+(?:\\.\\d+)+\\s*", "")
                .replaceFirst("^(?:[-*·•]|\\d+(?:\\.\\d+)+)\\s*", "")
                .replace("**", "");
        // Markdown 字段标签（「- 用法用量：…」「**适宜人群**：…」）是排版结构，不是剂量主体。
        // 人群限定（「成人每日 1 片」「儿童每日 1 粒」）不是剂量主体，不能当成裸主体；
        // 否则「成人每日 1 片」会被解析成主体「成人」，与原文的「维生素 D3 成人每日 1 片」对不上。
        body = STRUCTURE_FIELD_LABEL.matcher(body).replaceFirst("");


        // 「初次食用建议从每日 1 袋开始」：动作框架「初次食用建议」不是主体。
        // 只剥「食用/服用/摄入/补充 + 建议/推荐」这类明确的服用动作框架，避免
        // 把真实产品名「食用菌」等误剥；剥完仍以「从每日…」的既有规则继续。
        body = body.replaceFirst("^(?:初次)?(?:食用|服用|摄入|补充)(?:建议|推荐)(?:从)?(?=(?:每日|每天|每次))", "")
                .replaceFirst("^(?:初次建议|建议|推荐)?从(?=(?:每日|每天|每次))", "")
                // 地区/人群限定（「中国居民成人每日…」）不是剂量主体；它由 POPULATION 单独解析，
                // 留在主体里会让「中国居民」被当成一个药名，与原文的膳食纤维主体对不上。
                .replaceFirst("^(?:中国居民|我国居民|中国成人|中国成年人)(?=成人|成年人|每日|每天|推荐|摄入)", "");
        return QUANTITY_FRAME.matcher(VERSION_FRAME.matcher(body).replaceFirst("")).replaceFirst("")
                .replaceFirst("^(?:结论|具体风险|风险提示)[：:]", "")
                // 来源框架（“另外，平台知识库里有一份《X 说明书》，其中写明…”）不是主体：
                // 它声明的是这句话的出处，主体在框架之后的正文里。不剥掉会让
                // “其中写明”被 SUBJECT_HEAD 当成裸主体，与切片比对必然不一致。
                .replaceFirst("^(?:另外[，,]?)?(?:平台)?(?:知识库里?)?(?:有)?(?:一份)?《[^》]+》[，,]?(?:其中|里|中)?(?:也)?(?:写明|指出|说明|提到|记载)[：:,，]?", "")
                .replaceFirst("^(?:其中|该条|本条|原文|正文|说明书|资料|指南)(?:中|里)?(?:也)?(?:写明|指出|说明|提到|记载)[：:,，]?", "")
                .replaceFirst("^(?:(?:根据资料|根据说明书|同时|建议|推荐|(?:正在|在)?服用|摄入|补充|但|而|也|则|并|此外|另外|通常|一般|正常|健康|长期|首次|初次|一次|单次|(?:在)?(?:合用|同服)(?:期间|时)|"
                        + POPULATION_PREFIX.pattern() + ")[：:,，]*)+", "")
                .replaceFirst("^从(?=(?:每日|每天|每次))", "")
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
