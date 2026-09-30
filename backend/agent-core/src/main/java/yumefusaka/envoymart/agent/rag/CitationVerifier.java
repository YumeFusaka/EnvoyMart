package yumefusaka.envoymart.agent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用校验 —— 零幻觉的第三道闸，<b>也是唯一一道作用在生成之后</b>的闸。
 * <p>
 * 前两道（{@link EvidenceGate} 决定给不给证据、{@link KnowledgePrompt} 要求标注来源）
 * 都发生在模型开口之前。但 prompt 是<b>请求</b>，不是<b>保证</b>：模型可以标，也可以不标，
 * 而它没标的那句话，在界面上与标了引用的话长得一模一样。用户看到的是一段排版整齐、
 * 引经据典的回答，其中夹着一句凭常识补全的数字——没有任何东西提示他这一句没有出处。
 * 这道闸在答案出门前逐句核对，把「讲了一条事实却没交代出处」的句子挑出来。
 * <p>
 * <b>判据的分寸全在「什么算需要引用的句子」。</b>判太松（每句都要求引用）会误伤：
 * 一轮对话里模型完全可能既答了政策（该有引用）又答了订单状态（订单号来自工具返回，
 * 本来就没有引用可标）。判太严则等于没做。这里的取舍是三条：
 * <ul>
 *   <li><b>越界编号无条件摘除。</b>正文写着 {@code [7]} 而本轮只有 3 条证据，
 *       这是一个确定的错误，与「这句话该不该有引用」无关。</li>
 *   <li><b>先确认这是不是一个知识型回答。</b>整篇一个有效引用都没有时不做剔除——
 *       那说明这一轮压根不在答知识问题（订单、物流、闲聊），逐句剔会把回答砍碎。
 *       判据用「答里至少出现过一次有效引用」，它比任何关键词表都更贴近
 *       「连模型自己都认为这是知识型回答」。</li>
 *   <li><b>不剔除不等于不报告。</b>没有引用也没有工具执行记录的整篇回答，
 *       是模型凭自己写出来的——无出处的句子必须报出来（见 {@code hasToolEvidence}），
 *       否则它和一段有依据的回答在界面上毫无区别。</li>
 *   <li><b>违规过多则整体放弃剔除。</b>见 {@link #MAX_VIOLATION_RATIO}——
 *       一个标了引用却大半没标的回答，更像「模型整体没遵守格式」而不是
 *       「恰好这半句有问题」，此时全砍掉比留着更糟。</li>
 * </ul>
 * <p>
 * <b>这道闸对流式是滞后的。</b>流式下 delta 已经逐块推给前端了，校验只能改写 done 帧里的
 * 完整答案（前端会用 done 帧覆盖已渲染文本）。要做成「未校验的字不得到达用户」，
 * 唯一办法是先缓冲整个回答再发——那等于放弃流式。两害相权，选了可见的短暂闪烁。
 */
public final class CitationVerifier {

    private CitationVerifier() {
    }

    /** 正文里的引用角标。只认半角方括号加纯数字——{@link KnowledgePrompt} 就是这么要求的 */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

    /**
     * 断句：句末标点或换行，<b>标点跟随前一句</b>。
     * <p>
     * 靠匹配区间而不是「切分后重组」来定位：重组会把连续标点、缩进这类排版细节丢掉，
     * 而这里只需要知道每一句在原串里的 {@code [start, end)}，删除时按区间抠掉即可。
     */
    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;\n]+[。！？!?；;\n]*");

    /**
     * 一句话至少要多长才可能是一句断言。
     * <p>
     * 低于它的都当寒暄与过渡（「好的」「我帮你看看」）。中文里十来个字以内很难承载
     * 一条完整的事实陈述，而抬高这个阈值只会让更多真话漏网。
     */
    private static final int FACT_MIN_LENGTH = 12;

    /**
     * 出现这些词，说明这句话在陈述一条规则、标准或时效，而不是随口一说。
     * <p>
     * <b>数字单列</b>：金额、天数、浓度、上限——事实性断言绝大多数由它携带，
     * 而它也是编造代价最高的那一类。
     */
    private static final Pattern FACT_SIGNAL = Pattern.compile(
            "\\d|规定|政策|条款|标准|上限|下限|禁止|不得|必须|应当|期限|时效|有效期");

    /**
     * 违规句占比超过它就整体放弃剔除。
     * <p>
     * 三分之一：低于这个比例更像「个别句子漏标」，高于它更像「整篇没按格式来」。
     */
    private static final double MAX_VIOLATION_RATIO = 1.0 / 3.0;

    /** 违规句数不超过它时永远剔除，不看占比——三句话的回答里 1/3 是个过苛的尺子 */
    private static final int ALWAYS_STRIP_UP_TO = 3;

    /**
     * @param reply       清洗后的答案；未做剔除、也无需摘除编号时与输入逐字相同
     * @param unsupported 未通过校验的句子原文，按出现顺序；已剔除或仅被报告取决于 {@code stripped}。
     *                    <b>整篇没有一处有效引用时恒为空</b>——那不是知识型回答，
     *                    逐句点名既报不准（判据追不上自然语言的表达方式），也没有意义，
     *                    该说的是整篇没有依据，见 {@code ungrounded}
     * @param sentences   断句总数
     * @param cited       含<b>有效</b>引用（编号落在证据条数之内）的句数
     * @param stripped    违规句是否真的从 {@code reply} 里被剔除了
     * @param ungrounded  整篇回答是否<b>没有任何依据</b>——没有知识库引用，也没有工具执行记录，
     *                    内容是模型凭自身知识生成的
     */
    public record Verdict(String reply, List<String> unsupported, int sentences, int cited,
                          boolean stripped, boolean ungrounded) {

        /** 引用覆盖率，用于日志与评测；分母为 0 时记 1（无话可说的回答没有覆盖率问题） */
        public double coverage() {
            return sentences == 0 ? 1.0 : (double) cited / sentences;
        }
    }

    /**
     * @param reply           模型给出的完整答案
     * @param evidenceCount   本轮注入 prompt 的证据条数，即合法编号的上界
     * @param hasToolEvidence 本轮是否执行过工具。<b>决定「没有引用」该怎么解读</b>：
     *                        执行过——那一轮答的是业务问题，事实来自工具返回，无引用是正常的；
     *                        没执行过——整篇都是模型自己写的，无出处的事实句必须报出来。
     *                        不给这个信号就只能二选一，而两个方向各错一半
     */
    public static Verdict verify(String reply, int evidenceCount, boolean hasToolEvidence) {
        if (reply == null || reply.isBlank()) {
            return new Verdict(reply, List.of(), 0, 0, false, false);
        }

        List<String> unsupported = new ArrayList<>();
        // 违规句的区间先攒着：此刻还不知道最终会不会剔除（见下面的预算判断），
        // 但区间必须在这里取——断句的 Matcher 就握在手上，事后按文本回找会误伤重复句
        List<int[]> violationSpans = new ArrayList<>();
        // 越界编号按「原样 token」收集（形如 "[7]"），最后对全篇做一次替换。
        // 用 token 而不是行号做键：同一句里可能既有有效的 [1] 也有越界的 [7]，
        // 只按「这句含越界编号」就整句清空引用，会把那句合法的出处一起抹掉
        Set<String> outOfRange = new TreeSet<>();
        int sentences = 0;
        int cited = 0;

        Matcher matcher = SENTENCE.matcher(reply);
        while (matcher.find()) {
            String sentence = matcher.group();
            sentences++;

            Matcher refs = CITATION.matcher(sentence);
            while (refs.find()) {
                int no = Integer.parseInt(refs.group(1));
                if (no < 1 || no > evidenceCount) {
                    outOfRange.add(refs.group());
                }
            }
            // 摘掉越界编号之后才谈「这句话有没有引用」：
            // 「上限 4000IU [1][7]」摘掉 [7] 仍算有出处，而「上限 4000IU [7]」摘完就是一句无出处的断言
            String sanitized = without(sentence, outOfRange);

            if (hasValidCitation(sanitized, evidenceCount)) {
                cited++;
                continue;
            }
            if (needsCitation(sanitized)) {
                unsupported.add(sentence.trim());
                violationSpans.add(new int[] {matcher.start(), matcher.end()});
            }
        }

        // 「整篇没有一句有效引用」按非知识型回答处理：不剔除。
        // 这一轮压根不在答知识问题（订单号、物流时效、闲聊），
        // 逐句剔会把回答砍碎——他问的是包裹到哪了
        boolean knowledgeAnswer = cited > 0;
        boolean withinBudget = unsupported.size() <= ALWAYS_STRIP_UP_TO
                || unsupported.size() <= sentences * MAX_VIOLATION_RATIO;
        boolean stripped = knowledgeAnswer && withinBudget && !unsupported.isEmpty();

        // 报告分两级，用哪个取决于整篇有没有依据：
        //
        // 有引用时按句报——「这几句讲事实却没出处」是一次精准的修订，用户能对着改。
        // 没有引用也没有工具记录时**不逐句报，改报整篇**：这个场景下逐句点名既报不准
        // （判据靠数字与规范性词识别断言，追不上自然语言的表达方式——实测把
        // 「✅ 解答该商品的售后政策」这句能力陈述当成无出处的断言报了出来），
        // 也没有意义：用户需要知道的不是「哪几句有问题」，而是「这一整段都没有平台依据」。
        // 有工具执行记录时两者都不报——那一轮在答业务问题，事实来自工具返回。
        boolean ungrounded = !knowledgeAnswer && !hasToolEvidence;
        List<String> reported = knowledgeAnswer ? List.copyOf(unsupported) : List.of();

        // 顺序不能反：violationSpans 是按**原串**量出来的，先做全局替换会让后面所有
        // 区间错位（越界的 [7] 一删，它后面每一句的起止都往前挪了三个字符）。
        // 而全局替换本身与位置无关，什么时候做都对，所以把它排在后面
        String cleaned = reply;
        if (stripped) {
            cleaned = TextRanges.delete(cleaned, violationSpans);
        }
        if (!outOfRange.isEmpty()) {
            for (String token : outOfRange) {
                cleaned = cleaned.replace(token, "");
            }
            cleaned = TextRanges.tidy(cleaned);
        }
        return new Verdict(cleaned, reported, sentences, cited, stripped, ungrounded);
    }

    /** 把已判定越界的编号 token 从句子文本里去掉；没命中就原样返回 */
    private static String without(String sentence, Set<String> outOfRange) {
        String result = sentence;
        for (String token : outOfRange) {
            if (result.contains(token)) {
                result = result.replace(token, "");
            }
        }
        return result;
    }

    private static boolean hasValidCitation(String sentence, int evidenceCount) {
        Matcher refs = CITATION.matcher(sentence);
        while (refs.find()) {
            int no = Integer.parseInt(refs.group(1));
            if (no >= 1 && no <= evidenceCount) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这句话是否在陈述一条需要出处的事实。
     * <p>
     * 两条同时成立才算：够长（不是寒暄）、且带着事实信号（数字或规范性词）。
     * 这个判据刻意保守——它要放过「好的，我帮你查一下」，也要放过订单号与物流时效，
     * 那些数字来自工具返回，本来就没有引用可标。
     */
    private static boolean needsCitation(String sentence) {
        String trimmed = sentence.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.codePointCount(0, trimmed.length()) < FACT_MIN_LENGTH) {
            return false;
        }
        return FACT_SIGNAL.matcher(trimmed).find();
    }
}
