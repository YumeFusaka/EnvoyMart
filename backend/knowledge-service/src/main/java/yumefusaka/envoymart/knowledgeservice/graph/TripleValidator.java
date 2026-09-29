package yumefusaka.envoymart.knowledgeservice.graph;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.graph.EntityKind;
import yumefusaka.envoymart.agent.graph.GraphRelation;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeChunkEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 抽取结果的第一道闸：词表合规 + <b>引用必须能在原文里找到</b>。
 * <p>
 * 图上的每一条边都会被当成事实回答给用户（「这两个能不能一起吃」），
 * 所以入库的门槛不能是「模型说得像」。这里做的判定只有两条，但都是硬条件：
 * <ol>
 *   <li>头尾类型与关系都在封闭词表内，且关系允许这对类型；</li>
 *   <li>模型给出的原文引文必须<b>逐字出现在这篇文档的正文里</b>。</li>
 * </ol>
 * 第二条是关键。它把「模型根据常识补出来的一句话」挡在库外——模型当然知道
 * 华法林不能和鱼油乱吃，但图上不该出现一条<b>没有原文出处的边</b>，
 * 因为演示时点开引用会是空的，而空的引用比没有这条边更糟：它看起来像有依据。
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
        String body = docContent == null ? "" : docContent;

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
    private static Verdict validateOne(Triple t, String docId, String body,
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

        // 引文锚定。找不到原文就不入库——这是本类存在的主要理由
        String quote = t.quote() == null ? "" : t.quote().strip();
        int[] span = quote.isEmpty() ? null : locate(body, quote);
        if (span == null) {
            return Verdict.reject(RejectReason.UNGROUNDED);
        }

        return Verdict.accept(new GroundedTriple(headKind, headName, labelOr(t.headLabel(), t.headName()),
                relation, tailKind, tailName, labelOr(t.tailLabel(), t.tailName()),
                truncate(t.effect()), docId, chunkIdAt(chunks, span[0]), span[0], span[1], quote));
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
     * 实体名的规范化。<b>图谱的节点键就是它</b>，所以「维生素 D」「维生素D」「维生素d」
     * 必须落到同一个节点上——否则「鱼油 → EPA → 抗血小板 → 华法林」这条路径会因为
     * 中间一环多了一个空格而断开，而图上看起来只是「有两组相似的节点」，
     * 排查起来毫无线索。
     * <p>
     * 只做空白与大小写，<b>不做同义词归一</b>：把「维生素D3」并到「维生素D」是医学上
     * 错误的一步（D3 是 D 的一种形式，不是同义词），这种判断属于人工维护的别名表，
     * 不该由这里猜。
     */
    static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
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
     * 在正文中定位引文，返回 {@code [起始, 结束)} 的 UTF-16 偏移；找不到返回 {@code null}。
     * <p>
     * <b>忽略空白</b>匹配：模型抄原文时经常在中英文之间多一个空格或吞掉一个换行，
     * 逐字匹配会把这类引文判成幻觉——而它其实一字不差。做法是把正文与引文各压缩掉
     * 空白并记下每个压缩字符对应的原偏移，命中后再映射回来，高亮位置因此仍是精确的。
     */
    private static int[] locate(String body, String quote) {
        if (body.isEmpty()) {
            return null;
        }
        int[] map = new int[body.length()];
        StringBuilder compact = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (!Character.isWhitespace(c)) {
                map[compact.length()] = i;
                compact.append(c);
            }
        }

        StringBuilder needle = new StringBuilder(quote.length());
        for (int i = 0; i < quote.length(); i++) {
            char c = quote.charAt(i);
            if (!Character.isWhitespace(c)) {
                needle.append(c);
            }
        }
        if (needle.length() < MIN_QUOTE_CHARS) {
            return null;
        }

        int at = compact.indexOf(needle.toString());
        if (at < 0) {
            return null;
        }
        return new int[]{map[at], map[at + needle.length() - 1] + 1};
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
