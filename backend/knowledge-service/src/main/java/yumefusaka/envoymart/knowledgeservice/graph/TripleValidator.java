package yumefusaka.envoymart.knowledgeservice.graph;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.graph.EntityAliases;
import yumefusaka.envoymart.agent.graph.EntityAliases.Canonical;
import yumefusaka.envoymart.agent.graph.EntityKind;
import yumefusaka.envoymart.agent.graph.EntityNames;
import yumefusaka.envoymart.agent.graph.GraphRelation;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeChunkEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 抽取结果的第一道闸：词表合规 + <b>端点与引用都必须出自原文</b>。
 * <p>
 * 图上的每一条边都会被当成事实回答给用户（「这两个能不能一起吃」），
 * 所以入库的门槛不能是「模型说得像」。这里做的判定都是硬条件：
 * <ol>
 *   <li>头尾类型与关系都在封闭词表内，且关系允许这对类型；</li>
 *   <li>商品键是 {@code SPU<数字>}，名字不像一整句话；</li>
 *   <li>两端的名字至少有一个写法<b>出现在这篇文档的正文里</b>；</li>
 *   <li>模型给出的原文引文<b>逐字出现在这篇文档的正文里</b>。</li>
 * </ol>
 * 第三条和第四条合起来才挡得住「凭常识补边」。只有第四条时，模型补出一条
 * 华法林与阿司匹林的相互作用、再从文档里随便抄一句<b>逐字存在但无关</b>的句子，
 * 就能带着一份「看起来有依据」的伪溯源入库——而这比没有这条边更糟。
 * <p>
 * 校验失败一律<b>丢弃这一条</b>，不整批失败。一批几十条里混进一两条幻觉是常态，
 * 让整篇文档的图谱因此不建，是把小问题放大成不可用。
 */
@Slf4j
public final class TripleValidator {

    /** 引文长度下限（忽略空白后）。太短的引文落在哪都能匹配上，证明不了任何事 */
    private static final int MIN_QUOTE_CHARS = 6;
    /** 边上的后果说明上限，防止模型把一整段塞进属性 */
    private static final int MAX_EFFECT_CHARS = 200;
    /** 实体名长度上限。真实实体名都短，超长的多半是模型把一整句塞进了 name */
    private static final int MAX_NAME_CHARS = 60;
    /**
     * 商品节点键的形状，见 {@code KnowledgeGraphBuilder.key()}。
     * <p>
     * <b>服务端必须自己校验这个形状</b>，不能只靠客户端对齐：内部写入接口是
     * 直连端口就能调的，不校验的话谁都能往图上灌任意商品名字符串，
     * 而那些节点永远连不上商品目录，查询时表现为「这个商品没有已知相互作用」
     * ——一个不报错的错误答案。规范化后是小写，所以这里也用小写
     */
    private static final Pattern SPU_KEY = Pattern.compile("spu\\d+");
    /** 丢弃日志里引文的展示长度。判断错因不需要看完整段 */
    private static final int QUOTE_LOG_CHARS = 60;

    private TripleValidator() {
    }

    /**
     * 丢弃的原因。
     * <p>
     * <b>为什么要分得这么细</b>：三者对应的处置完全不同，合并成一个数字就没法判断。
     * 第一版只分了「词表 / 无出处」两类，且「无出处」是靠事后重算一遍推出来的——
     * 于是自环与空名这种既不属于词表、引文又能找到的条目，被静默算进了「词表」。
     * 实测 KB-0005 十抽十丢、日志写着「词表 10 / 无出处 0」，看上去像提示词与枚举对不上，
     * 实际上一次词表都没查过。归因错了比不归因更贵：它让人去改一个没坏的地方。
     */
    public enum RejectReason {
        /** 类型或关系不在词表，或关系不接受这对类型。原因八成在提示词与枚举不一致 */
        VOCABULARY("词表"),
        /** 头尾同名或名字为空。自环对「A 和 B 冲突吗」这类查询没有意义 */
        SELF_LOOP("自环"),
        /** 商品键不是 SPU 形式，或名字长得不像实体名。原因在客户端没做实体链接，或模型跑飞 */
        MALFORMED("畸形"),
        /** 端点根本没在这篇文档里出现。原因在模型拿常识补边、引文随便抄了一句 */
        UNANCHORED("端点无出处"),
        /** 引文在原文里找不到。原因在模型改写引文或凭常识编边 */
        UNGROUNDED("无出处");

        private final String label;

        RejectReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param accepted 通过校验并已锚定原文位置的三元组
     * @param reasons  每种 {@link RejectReason} 各丢弃了多少条
     */
    public record Result(List<GroundedTriple> accepted, Map<RejectReason, Integer> reasons) {

        public int rejected() {
            return reasons.values().stream().mapToInt(Integer::intValue).sum();
        }

        public int count(RejectReason reason) {
            return reasons.getOrDefault(reason, 0);
        }

        /** 形如 {@code 词表 3 / 自环 0 / 无出处 1}。给日志用，顺序固定，便于逐个版本对比 */
        public String breakdown() {
            return Arrays.stream(RejectReason.values())
                    .map(r -> r.label() + " " + count(r))
                    .collect(Collectors.joining(" / "));
        }
    }

    /**
     * @param candidates 模型抽取的候选三元组
     * @param docId      候选的来源文档
     * @param docContent 文档<b>正文</b>。注意不是切片 content——切片前拼了
     *                   {@code 《标题》 > 章} 的位置前缀，那段字在原文里并不存在，
     *                   拿切片文本做匹配会把位置前缀误当成依据
     * @param chunks     该文档的切片，用于把命中偏移映射回所属切片
     */
    public static Result validate(List<Triple> candidates, String docId, String docContent,
                                  List<KnowledgeChunkEntity> chunks) {
        if (candidates == null || candidates.isEmpty()) {
            return new Result(List.of(), Map.of());
        }
        // 正文压缩一次，整批共用。原先每条引文都重建一遍压缩串与偏移表，
        // 一篇文档几十条就是几十次全量扫描——而那份结果每次都完全一样
        Compact body = Compact.of(docContent == null ? "" : docContent);

        List<GroundedTriple> accepted = new ArrayList<>();
        Map<RejectReason, Integer> reasons = new EnumMap<>(RejectReason.class);
        for (Triple t : candidates) {
            Verdict verdict = validateOne(t, docId, body, chunks);
            if (verdict.grounded() != null) {
                accepted.add(verdict.grounded());
            } else {
                reasons.merge(verdict.reason(), 1, Integer::sum);
                // 逐条打出被丢弃的字段值。原先只打条数，于是「词表 10」既可能是模型返回了
                // 中文标签、也可能是关系配错了类型，只能靠重启一次改一处日志来二分。
                // 这条日志只在重建索引时产出，量很小，换来的是「看一眼就知道错在哪」
                log.info("[Graph] 丢弃（{}）：{}={} -{}-> {}={} | quote={}",
                        verdict.reason().label(), t.headKind(), t.headName(), t.relation(),
                        t.tailKind(), t.tailName(), abbreviate(t.quote()));
            }
        }
        return new Result(accepted, reasons);
    }

    private record Verdict(GroundedTriple grounded, RejectReason reason) {

        static Verdict accept(GroundedTriple t) {
            return new Verdict(t, null);
        }

        static Verdict reject(RejectReason reason) {
            return new Verdict(null, reason);
        }
    }

    /**
     * 校验单条，<b>并且说清是哪一条不通过</b>。
     * <p>
     * 返回值带原因而不是「判丢返回 null、调用方再猜一次」：猜的那一版必须重算一遍判定，
     * 而重算的结果与主路径只要有一处不一致，日志就会指向错误的方向。
     */
    private static Verdict validateOne(Triple t, String docId, Compact body,
                                       List<KnowledgeChunkEntity> chunks) {
        if (t == null) {
            return Verdict.reject(RejectReason.VOCABULARY);
        }
        EntityKind headKind = EntityKind.parse(t.headKind());
        EntityKind tailKind = EntityKind.parse(t.tailKind());
        GraphRelation relation = GraphRelation.parse(t.relation());
        if (headKind == null || tailKind == null || relation == null) {
            return Verdict.reject(RejectReason.VOCABULARY);
        }
        // 组合禁忌走另一条分支：头是「组合」节点（不是实体），形状与锚定规则都不一样。
        // 分出去而不是在这里加一串 if，是因为两者的判据几乎不重叠——
        // 混在一起写会让「单跳到组合」的每一处判断都要重新想一遍它适不适用
        if (relation == GraphRelation.COMBINED_WITH) {
            return validateCombination(t, docId, body, chunks, tailKind);
        }

        // 模型写的名字，先留一份：下面锚定要拿它去正文里找
        String headWritten = normalizeName(t.headName());
        String tailWritten = normalizeName(t.tailName());

        // 归一到规范实体。放在锚定<b>之前</b>是因为形状校验要用规范类型
        //（别名表会把「强心苷类药物」从 DRUG 纠正成 DRUG_CLASS）；
        // 但锚定本身仍然用模型写的那个名字——见下面 anchors 处的说明
        Canonical head = canonical(headWritten, headKind);
        Canonical tail = canonical(tailWritten, tailKind);

        // 自环与空名判定<b>排在关系类型之前</b>。别名归并会顺带改类型，
        // 于是「富马酸亚铁 -[X]-> 铁剂」这类条目在归并后同时是自环和类型不符——
        // 两条都成立时报哪一条？报自环。模型的真实毛病是「把同一个东西的两端拼成了一对」，
        // 类型不符只是归并的副产物；报词表会把人送去改提示词与枚举，而那里没坏
        if (head.name().isEmpty() || tail.name().isEmpty() || head.name().equals(tail.name())) {
            // 判据用<b>规范名</b>：别名表可能把原本不同的两端并成同一个
            return Verdict.reject(RejectReason.SELF_LOOP);
        }

        if (!relation.acceptsHead(head.kind()) || !relation.acceptsTail(tail.kind())) {
            return Verdict.reject(RejectReason.VOCABULARY);
        }
        if (head.name().length() > MAX_NAME_CHARS || tail.name().length() > MAX_NAME_CHARS
                || !wellShaped(head.kind(), head.name()) || !wellShaped(tail.kind(), tail.name())) {
            return Verdict.reject(RejectReason.MALFORMED);
        }

        // 端点锚定在前、引文锚定在后：端点根本不在文档里，说明这一条是凭空造的，
        // 那比「引文抄错了」更根本，报出来的原因也更接近真实错因。
        //
        // 这里必须用模型写的名字，不能用规范名。别名表把「富马酸亚铁」并到了
        // 「铁剂」，而文档里写的是前者——拿规范名去正文里找，这一条会被判成
        // 「端点无出处」整体丢掉。<b>并入一个更通用的名字，不该让一条本来有出处的边变成没出处。</b>
        // **声明补出的商品→成分边，头端不参与锚定。**
        // 它的头是「这篇文档属于哪个商品」，而一份说明书通篇写「本品」，正文里**不可能**
        // 出现商品名，更不会出现 SPU 编号——要求头端锚定，等于要求这条边永远进不来。
        // 实测正是如此：补出的边全部倒在「端点无出处」，13 个商品至今没有节点。
        // 头端的依据是关联表里的人工声明（比正文锚定更强的证据），尾端（成分）照常锚定。
        boolean anchored = t.declared()
                ? body.anchors(tailKind, tailWritten, t.tailLabel())
                : body.anchors(headKind, headWritten, t.headLabel())
                        && body.anchors(tailKind, tailWritten, t.tailLabel());
        if (!anchored) {
            return Verdict.reject(RejectReason.UNANCHORED);
        }

        // **声明补出的商品→成分边免引文校验。**
        // 它的依据是两个已成立的事实之组合：文档的归属由运营在上传时人工声明（在关联表里），
        // 成分由模型从正文里抽出并已过引文校验。它不是从某一句话推出来的，所以没有那句话可引——
        // 而这两端仍然要锚定（上面那道检查照跑），端点凭空造出来的一样会被拦。
        // 判据是显式的 declared 标志，不是「引文为空」：后者会给所有幻觉边开门。
        // 引文锚定。找不到原文就不入库——这是本类存在的主要理由。
        // 声明补出的边（declared=true）没有那句话可引，跳过拒绝判定；
        // 但上面的端点锚定照跑，端点凭空造出来的一样会被拦。
        String quote = t.quote() == null ? "" : t.quote().strip();
        int[] span = body.locate(quote);
        if (span == null) {
            if (!t.declared()) {
                return Verdict.reject(RejectReason.UNGROUNDED);
            }
        }

        return Verdict.accept(new GroundedTriple(head.kind(), head.name(),
                displayLabel(head, headWritten, t.headLabel(), t.headName()),
                relation, tail.kind(), tail.name(),
                displayLabel(tail, tailWritten, t.tailLabel(), t.tailName()),
                truncate(t.effect()), docId,
                span == null ? null : chunkIdAt(chunks, span[0]),
                span == null ? null : span[0], span == null ? null : span[1], quote));
    }

    /**
     * 组合禁忌的校验路径 —— 「两两没事、三样一起有事」这一条。
     * <p>
     * <b>与单跳校验的三处不同，每一处都是必须的：</b>
     * <ol>
     *   <li><b>头是组合节点，不是实体。</b>模型给出的是参与组合的若干物质名
     *       （{@code headName} 里用 {@code +} 连接，如 {@code 铁剂+钙剂}），
     *       这里把每个成员归一到规范名、去重、按字典序排序，拼成
     *       {@code combo:铁剂|钙剂}。排序是必须的：「钙+铁」与「铁+钙」是同一个组合，
     *       不排序的话同一件事会在图上分成两个节点、条数各算一半。</li>
     *   <li><b>成员必须都出现在正文里。</b>成员一个都不在原文中的组合是模型凭常识拼的，
     *       整条丢弃——这条挡的是「把两条互不相干的边凑成一个组合」。</li>
     *   <li><b>引文必须同时提到全部成员。</b>这是本类最要紧的一条判据：
     *       文档里单独一句「铁剂与钙剂同服影响吸收」<b>不能</b>支撑
     *       「铁剂+钙剂+维生素D 三样一起有事」——那句话里没有维生素 D。
     *       少了这一条，模型只要找到任意一句提到两个成员的话，就能把一个更大的组合
     *       挂上去，而图上看起来有完整出处。这是最容易静默出错的地方。</li>
     * </ol>
     * <b>至少两个成员</b>：一个成员的「组合」与单跳是一回事，走 {@code INTERACTS_WITH}
     * 表达即可，不该多出一条形状不同的边把同一件事说两遍。
     */
    private static Verdict validateCombination(Triple t, String docId, Compact body,
                                               List<KnowledgeChunkEntity> chunks,
                                               EntityKind tailKind) {
        List<String> members = combinationMembers(t.headName());
        if (members.size() < 2) {
            return Verdict.reject(RejectReason.MALFORMED);
        }
        String comboKey = combinationKey(members);
        if (comboKey.length() > MAX_NAME_CHARS) {
            return Verdict.reject(RejectReason.MALFORMED);
        }

        // 成员的展示名：优先用模型给的原写法（「维生素 D3」比「维生素d3」好读），
        // 归一到规范名后再拼，保证同一个组合的标签每次一样
        String display = combinationDisplay(t.headName(), members);

        String tailWritten = normalizeName(t.tailName());
        Canonical tail = canonical(tailWritten, tailKind);
        if (tail.name().isEmpty()) {
            return Verdict.reject(RejectReason.SELF_LOOP);
        }
        if (!GraphRelation.COMBINED_WITH.acceptsTail(tail.kind())) {
            return Verdict.reject(RejectReason.VOCABULARY);
        }
        if (tail.name().length() > MAX_NAME_CHARS || !wellShaped(tail.kind(), tail.name())) {
            return Verdict.reject(RejectReason.MALFORMED);
        }

        // 引文要同时提到全部成员 —— 见方法注释第三条
        String quote = t.quote() == null ? "" : t.quote().strip();
        int[] span = quote.isEmpty() ? null : body.locate(quote);
        if (span == null) {
            return Verdict.reject(RejectReason.UNGROUNDED);
        }
        String compactQuote = EntityNames.normalize(quote);
        for (String member : members) {
            if (!compactQuote.contains(member)) {
                // 成员不在引文里 = 这句话支撑不了这个组合，报 UNANCHORED 而不是 UNGROUNDED：
                // 引文本身是逐字命中的，缺的是「它说的不是这件事」
                return Verdict.reject(RejectReason.UNANCHORED);
            }
        }
        if (!body.anchors(tail.kind(), tailWritten, t.tailLabel())) {
            return Verdict.reject(RejectReason.UNANCHORED);
        }

        return Verdict.accept(new GroundedTriple(EntityKind.COMBINATION, comboKey, display,
                GraphRelation.COMBINED_WITH, tail.kind(), tail.name(),
                displayLabel(tail, tailWritten, t.tailLabel(), t.tailName()),
                truncate(t.effect()), docId, chunkIdAt(chunks, span[0]), span[0], span[1], quote));
    }

    /**
     * 拆组合成员并归一化。
     * <p>
     * 分隔符多收几种是必要的：模型有时给 {@code 铁剂+钙剂}、有时给
     * {@code 铁剂、钙剂}、有时给英文逗号。只认一种的话，「分隔符写法不同」
     * 会表现为「这个组合没有收录」，而日志上看到的是一条正常抽出来的三元组被丢弃。
     * <p>
     * 归一是<b>先做</b>的：别名表会把「富马酸亚铁」并到「铁剂」，如果先拼键再归一，
     * 「富马酸亚铁+钙剂」与「铁剂+钙剂」会变成两个组合节点。
     */
    static List<String> combinationMembers(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[+＋、,，;；|]"))
                .map(TripleValidator::normalizeName)
                .map(TripleValidator::canonicalName)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 组合的展示标签：成员用<b>模型写的原写法</b>，顺序与键一致。
     * <p>
     * <b>为什么要单独算一次，而不直接用键。</b>键是规范化过的（去空白、转小写），
     * 拿它当标签会让界面上出现「维生素d + 钙 + 铁剂」——<b>小写的 d</b> 是规范化
     * 的副产物，对人来说是错的。这里保留原始写法（「维生素D」），
     * 同时按成员键的排序顺序输出，使标签与键的成员顺序对得上。
     * <p>
     * 取不到原写法时（成员是别名归一来的、原文里没写）退回成员键本身：
     * 宁可显示「铁剂」这种归一后的名字，也不要显示空白或漏掉一个成员。
     */
    private static String combinationDisplay(String raw, List<String> members) {
        if (raw == null || raw.isBlank()) {
            return String.join(" + ", members);
        }
        // 原始写法按「归一后 → 原名」建一次映射，再按 members（已排序）的顺序取，
        // 这样顺序跟着键走，大小写跟着模型走
        java.util.Map<String, String> written = new java.util.LinkedHashMap<>();
        for (String part : raw.split("[+＋、,，;；|]")) {
            String stripped = part.strip();
            String normalized = canonicalName(normalizeName(stripped));
            // 同一个成员出现两次时保留第一次的写法
            written.putIfAbsent(normalized, stripped);
        }
        return members.stream()
                .map(m -> written.getOrDefault(m, m))
                .collect(Collectors.joining(" + "));
    }

    /** 组合节点的键。成员<b>必须已归一且排过序</b>，见 {@link #combinationMembers} */    /** 组合节点的键。成员<b>必须已归一且排过序</b>，见 {@link #combinationMembers} */
    static String combinationKey(List<String> sortedMembers) {
        return EntityKind.COMBINATION_PREFIX + String.join("|", sortedMembers);
    }

    /**
     * 解析到规范实体；别名表里没有这个写法就<b>沿用原名原类型</b>。
     * <p>
     * 返回一个非空对象而不是可空的 {@link EntityAliases.Canonical}：
     * 调用方每一条三元组都要用两个端点，让「没命中」也返回一个对象，
     * 下面就不必写四次判空——而少写一次判空就是一个 NPE，或者更糟：
     * 一个把 null 当成名字传下去的静默错误。
     */
    private static EntityAliases.Canonical canonical(String normalizedName, EntityKind kind) {
        EntityAliases.Canonical hit = EntityAliases.resolve(normalizedName);
        return hit != null ? hit : new EntityAliases.Canonical(normalizedName, kind);
    }

    /**
     * 归一到规范实体名；表里没有这个写法就原样返回。
     * <p>
     * 给<b>查询侧</b>用（{@code GraphService.interactions}）：那边手上只有用户说的一串名字，
     * 没有类型可给，而表里的条目都自带类型，所以给不给都一样。
     */
    public static String canonicalName(String normalizedName) {
        EntityAliases.Canonical hit = EntityAliases.resolve(normalizedName);
        return hit == null ? normalizedName : hit.name();
    }

    /** 商品键必须是 {@code SPU<数字>}；其余类型不限形状，长度已在上游卡住 */
    private static boolean wellShaped(EntityKind kind, String name) {
        return kind != EntityKind.PRODUCT || SPU_KEY.matcher(name).matches();
    }

    /** 引文只用来判断「错在哪一类」，日志里截短即可，不必占满一行 */
    private static String abbreviate(String quote) {
        if (quote == null || quote.isBlank()) {
            return "(空)";
        }
        String s = quote.strip();
        return s.length() <= QUOTE_LOG_CHARS ? s : s.substring(0, QUOTE_LOG_CHARS) + "…";
    }

    /**
     * 端点被别名归并时，展示名跟着归到规范名。
     * <p>
     * <b>不跟会怎样</b>：节点键是「铁剂」而标签留着「富马酸亚铁」，前端点开这个节点看到的
     * 是旧名字，而它底下的边属于「铁剂」——一个节点两副面孔。更麻烦的是标签由
     * {@code SET n.label = ...} 覆盖写，同一个节点会被不同文档写上不同的标签，
     * 后写的赢，结果连「显示成哪个」都不确定。归到规范名之后每篇文档写的都一样。
     */
    private static String displayLabel(Canonical canonical, String written, String label, String rawName) {
        return canonical.name().equals(written) ? labelOr(label, rawName) : canonical.name();
    }

    /**
     * 展示名缺省由节点键兜底。
     * <p>
     * 兜底时用<b>原始</b>写法而不是规范化后的键：规范化会去掉空格，
     * 而「碳酸钙 D3 咀嚼片」这个商品名里空格是名字的一部分，显示成「碳酸钙d3咀嚼片」
     * 虽然能读，但和商品页上的名字对不上，用户会以为是两个东西。
     */
    private static String labelOr(String label, String name) {
        String s = label == null ? "" : label.strip();
        return s.isEmpty() ? (name == null ? "" : name.strip()) : s;
    }

    /**
     * 实体名的规范化。<b>图谱的节点键就是它</b>。
     * <p>
     * 实现搬到了 {@link EntityNames}：ai-service 建商品键时用的是同一个函数，
     * 而两处各写一遍的后果是「改了一边忘了另一边」，图上同一个实体分裂成两个节点。
     */
    static String normalizeName(String name) {
        return EntityNames.normalize(name);
    }

    private static String truncate(String effect) {
        if (effect == null) {
            return null;
        }
        String s = effect.strip();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() <= MAX_EFFECT_CHARS ? s : s.substring(0, MAX_EFFECT_CHARS);
    }

    /**
     * 去掉空白后的正文，外加「压缩串第 i 个字符在原串里的偏移」。
     * <p>
     * <b>忽略空白</b>匹配：模型抄原文时经常在中英文之间多一个空格或吞掉一个换行，
     * 逐字匹配会把这类引文判成幻觉——而它其实一字不差。把正文压缩掉空白并记下
     * 每个压缩字符对应的原偏移，命中后再映射回来，高亮位置因此仍是精确的。
     * <p>
     * {@code lower} 是同一串的小写副本，只给「端点是否出自本文」的包含判断用。
     * 让 {@code locate} 用原串而不是小写串：大小写要用来定位原文偏移，
     * 换掉的话命中位置会整体对不上。
     */
    private record Compact(String text, String lower, int[] map) {

        static Compact of(String body) {
            StringBuilder sb = new StringBuilder(body.length());
            StringBuilder lo = new StringBuilder(body.length());
            int[] map = new int[body.length()];
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (!EntityNames.isBlank(c)) {
                    map[sb.length()] = i;
                    sb.append(c);
                    lo.append(Character.toLowerCase(c));
                }
            }
            return new Compact(sb.toString(), lo.toString(), map);
        }

        /** 定位引文，返回 {@code [起始, 结束)} 的 UTF-16 偏移；找不到返回 {@code null} */
        int[] locate(String quote) {
            String needle = EntityNames.stripBlanks(quote);
            if (needle.length() < MIN_QUOTE_CHARS) {
                return null;
            }
            int at = text.indexOf(needle);
            return at < 0 ? null : new int[]{map[at], map[at + needle.length() - 1] + 1};
        }

        /**
         * 端点是否出自这篇文档。
         * <p>
         * <b>只校验引文是不够的</b>：模型完全可以凭常识补出一条
         * 「华法林 -INTERACTS_WITH-> 阿司匹林」，再从文档里抄一句逐字存在、
         * 但与这条关系无关的句子当引文——于是这条边带着一份<b>看起来有依据</b>的
         * 伪溯源入库，比没有这条边更糟。要求两端至少有一个写法出现在正文里，
         * 挡掉的正是「两个端点都是凭空造的」这一类。
         * <p>
         * 商品端直接放行：文档里写的是「本品」，而节点键是 SPU 编号，
         * 正文里当然找不到。它与目录的对齐在 ai-service 做（那边才拿得到目录），
         * 这里只认键的形状（见 {@link #wellShaped}）。
         */
        boolean anchors(EntityKind kind, String name, String label) {
            if (kind == EntityKind.PRODUCT) {
                return true;
            }
            if (lower.contains(name)) {
                return true;
            }
            // label 常常是空的（模型只给 name），而**空串被任何字符串包含**——
            // 少写这个 isEmpty 判断的话这一整条校验恒为 true，形同不存在。
            // 这不是假设：第一版就是这样，测试里「两个端点都是编的」那条照样入库
            String byLabel = EntityNames.normalize(label);
            return !byLabel.isEmpty() && lower.contains(byLabel);
        }
    }

    /**
     * 命中偏移落在哪个切片里。切片的 {@code charOffset} 指向<b>块首</b>（含被从正文里
     * 去掉的标题行），所以判据是「最后一个起点不晚于该偏移的切片」，而不是区间包含——
     * 引用落在块首行上时区间包含会判空，而那恰恰是最常见的情况。
     * <p>
     * 取不到就返回 null：引用仍然指向正确的文档，只是少了一层放大镜。
     */
    private static String chunkIdAt(List<KnowledgeChunkEntity> chunks, int offset) {
        if (chunks == null || chunks.isEmpty()) {
            return null;
        }
        KnowledgeChunkEntity best = null;
        for (KnowledgeChunkEntity c : chunks) {
            if (c.getCharOffset() == null || c.getCharOffset() > offset) {
                continue;
            }
            if (best == null || c.getCharOffset() > best.getCharOffset()) {
                best = c;
            }
        }
        return best == null ? null : best.getChunkId();
    }
}
