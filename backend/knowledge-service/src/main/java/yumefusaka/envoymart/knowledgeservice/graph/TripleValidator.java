package yumefusaka.envoymart.knowledgeservice.graph;

import lombok.extern.slf4j.Slf4j;
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
        if (!relation.acceptsHead(headKind) || !relation.acceptsTail(tailKind)) {
            return Verdict.reject(RejectReason.VOCABULARY);
        }

        String headName = normalizeName(t.headName());
        String tailName = normalizeName(t.tailName());
        if (headName.isEmpty() || tailName.isEmpty() || headName.equals(tailName)) {
            // 自环对「A 和 B 冲突吗」这类查询没有意义，一律不要
            return Verdict.reject(RejectReason.SELF_LOOP);
        }
        if (headName.length() > MAX_NAME_CHARS || tailName.length() > MAX_NAME_CHARS
                || !wellShaped(headKind, headName) || !wellShaped(tailKind, tailName)) {
            return Verdict.reject(RejectReason.MALFORMED);
        }

        // 端点锚定在前、引文锚定在后：端点根本不在文档里，说明这一条是凭空造的，
        // 那比「引文抄错了」更根本，报出来的原因也更接近真实错因
        if (!body.anchors(headKind, headName, t.headLabel())
                || !body.anchors(tailKind, tailName, t.tailLabel())) {
            return Verdict.reject(RejectReason.UNANCHORED);
        }

        // 引文锚定。找不到原文就不入库——这是本类存在的主要理由
        String quote = t.quote() == null ? "" : t.quote().strip();
        int[] span = quote.isEmpty() ? null : body.locate(quote);
        if (span == null) {
            return Verdict.reject(RejectReason.UNGROUNDED);
        }

        return Verdict.accept(new GroundedTriple(headKind, headName, labelOr(t.headLabel(), t.headName()),
                relation, tailKind, tailName, labelOr(t.tailLabel(), t.tailName()),
                truncate(t.effect()), docId, chunkIdAt(chunks, span[0]), span[0], span[1], quote));
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
