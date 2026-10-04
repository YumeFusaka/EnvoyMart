package yumefusaka.envoymart.agent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 证据冲突的抽取 —— 零幻觉的第二道闸（三道：拒答门、冲突显式呈现、引用覆盖）。
 * <p>
 * <b>要解决的问题是「模型替用户做了选择」。</b>同一个问题在两份文档里有不同说法时
 * （说明书说上限 2000IU、膳食指南说上限折合 2000IU 但另一份指南写 4000IU），
 * 把三条证据一起丢给模型，它会流畅地挑一条讲出来——用户拿到的是一句斩钉截铁的话，
 * 而系统手里其实握着一个尚未解决的矛盾。企业场景里这比拒答更糟：它把「资料打架」
 * 伪装成了「查证完毕」。
 * <p>
 * <b>为什么检测交给模型、抽取留给规则。</b>判断两段文字是否互相矛盾，是模型擅长而正则
 * 完全做不到的事（「按比例扣回」与「从退款中扣回相应优惠」是同一件事的两种说法，
 * 「2000IU」与「50 微克」是同一个数字的两种单位——字面比对两头都会判错）。
 * 但反过来，<b>让模型直接吐 JSON 是不可靠的</b>：它会夹带解释、会漏字段、会在
 * JSON 外面裹一层 markdown 代码块，解析失败时整条链路就断了。所以这里让它在正文里
 * 按固定标记写一段人话，再用规则把这段抽出来——<b>模型只负责判断，格式由 prompt 约束，
 * 结构化由正则兜底</b>。抽不出来最坏退化成「冲突以文字形式留在回答里」，用户仍然看得见，
 * 不会静默丢失。
 * <p>
 * 冲突不从回答里删掉而是抽成结构化字段，是为了让前端把它渲染成一张能点回原文的卡片：
 * 一段「【冲突】条目 1 与条目 3……」的纯文本摆在回答末尾，用户既不知道条目 1 是哪一篇，
 * 也没法自己去核对。
 */
public final class ConflictReporter {

    private ConflictReporter() {
    }

    /**
     * 冲突段落的起始标记。<b>与 {@link KnowledgePrompt} 里给模型的格式要求逐字对应</b>
     * ——两处写的是同一个字符串常量，不是两处巧合相同的字面量。
     * <p>
     * 用全角方括号而不是 {@code [冲突]}：半角方括号已经被引用角标占了
     * （{@code [1]}、{@code [2]}），两种标记同形会让 {@link CitationVerifier} 的
     * 角标正则把冲突行的日期、编号一起当成引用去校验。
     */
    public static final String MARKER = "【冲突】";

    /**
     * 独立的冲突核对提示词 —— <b>输入只有「当前问题 + 候选证据」，不带主对话上下文</b>。
     * <p>
     * <b>为什么必须单独成一次调用，而不是在主回答里让模型顺带判定。</b>
     * 主回答那一轮带着整段对话历史，而历史里往往已经做过一次「核对下来无冲突」的结论
     * （前面几轮问同一批资料时判过一致）。模型一旦在历史里看到那条结论，
     * 后续轮次就不再逐条比对，直接沿用——实测铁问句放进真实顺序
     * （先问维生素 D、再问铁）时命中率从无上下文的 12/12 掉到 40 发中 6 发漏报。
     * <p>
     * <b>隔离的判据是「这一轮该不该报冲突」只由「这一轮的问题与证据」决定</b>，
     * 与「前几轮聊过什么」无关。所以这里把历史整段拿掉，只留问题与证据条目。
     * <p>
     * 输出格式与主提示词里的约定<b>逐字相同</b>（同一套 {@link #MARKER} 标记），
     * 抽取仍由 {@link #extract} 完成——模型只负责判断，结构化由正则兜底。
     */
    public static final String CHECK_PROMPT = """
            你是资料一致性核对员。下面给出用户当前的问题，以及平台知识库检索到的若干条候选证据。
            你的唯一任务是：判断这些证据之间，对**同一件事**是否给出了**不一样的数字、期限或结论**，
            并在能判定时**给出结论**。

            【第一步：判断是否存在冲突】
            判定标准只有一条：两份证据对同一件事给出不同的数字、期限或结论。
            - 同一个数换了单位或写法（「10 微克（折合 400 国际单位）」与「400IU」）——不是冲突。
            - 只是详略不同、适用范围不同（总则与特别规定、通用规则与特定类目）——不是冲突。
            - 证据只涉及一件、或结论互相印证——不是冲突。
            没有冲突时，只输出四个字：无冲突

            【第二步：有冲突时先尝试定夺，定不了才上报】
            每条证据都标了「版本」。先看版本：
            - **同一份文档的不同版本**（如 v2026.03 与 v2026.01）之间说法不同 —— 这不算冲突，
              直接以**版本较新**的那一份为准，不要报冲突。新版本取代旧版本是资料维护的常态，
              把版本演进口径当矛盾上报，会让每一份改过的文档都亮一次红灯。
            - **不同文档**之间说法不同，且版本号能分出先后（一方明显更新）—— 同样以较新的为准，
              不报冲突，但要在结论里**注明你用的是哪一份**。
            - 版本号看不出先后、或属于同一版本——**这才是真正需要人工确认的冲突**，如实上报。
            - 规格、适用人群、剂型等**本身就该不同**的并列条目（如成人款与儿童款的剂量），
              不是冲突。

            存在冲突时必须只在回答最后另起一行，以「%s」开头，二选一：
            能定夺时写：哪几条不同、各自说了什么、**以哪一份为准**（写清文档名与版本），
            并简短说明依据（版本更新）。
            定不了时写：哪几条不同、各自说了什么、**为何无法判定先后**、需人工确认。
            例如——
            %s条目 1 与条目 3 对每日上限给出不同数字：条目 1（v2026.01）为 2000IU，条目 3（v2026.04）为 4000IU，以条目 3 为准（版本更新）。
            %s条目 2 与条目 5 对退货期限给出不同数字：条目 2 为 7 天、条目 5 为 15 天，两份版本号同为 v2026.02 且适用类目重叠，无法判定先后，需人工确认以哪一份为准。

            只输出核对结论，不要复述证据、不要给用户建议、不要写任何其他内容。
            """.formatted(MARKER, MARKER, MARKER);

    /** 段落起始：允许前面带列表符号与缩进——模型常把它写成列表项 */
    private static final Pattern HEAD = Pattern.compile("(?m)^[ \t>*•-]*" + MARKER);

    /**
     * 从冲突段落里认出它提到的证据编号。
     * <p>
     * 三种写法都认：{@code [1]}（与正文角标同形）、{@code 条目 1}、{@code 第 1 条}。
     * 认不出来的不猜——{@code refs} 为空时前端只展示文字，不给跳转链接；
     * 猜错的链接比没有链接更坏，它会把用户带到一条无关的原文面前。
     */
    private static final Pattern REF = Pattern.compile("\\[(\\d+)]|条目\\s*(\\d+)|第\\s*(\\d+)\\s*条");

    /**
     * 模型自陈「核对下来并无分歧」的结论词。
     * <p>
     * prompt 里已经写明「确认过一致的事不要写进这个段落」，但模型仍会偶发地
     * 把一次核对过程写进标记里。实测原话：条目 1 写「10 微克（折合 400 国际单位）」、
     * 条目 2 写「400IU」，模型核对后自己写着「数值一致，不构成实质冲突」，
     * 却仍然起了这个段落——用户于是收到一张冲突卡片，以为平台资料打架了。
     * 这是<b>采样随机性</b>：改措辞只能压低概率，消不掉，所以这里必须兜一层。
     * <p>
     * <b>认的是结论词，不是措辞清单。</b>第一版写成了「不构成(实质)?(矛盾|冲突)」
     * 这样的枚举，下一轮模型就换了说法——「三者在 400IU/10 微克这一换算关系上一致，
     * 无实质冲突」——一个都没命中。模型的表达方式追不完，能追的只有「它有没有说
     * 两边一致」这个语义本身。
     * <p>
     * <b>这不是替模型做判断</b>——判断是它做的，它已经在文字里给出了结论，
     * 抽取层只是把这个结论读出来执行。真正需要防的是把「一致」认错，
     * 见 {@link #DISAGREES} 与 {@link #NEEDS_HUMAN}。
     */
    private static final Pattern CONSISTENT = Pattern.compile(
            "一致|无(实质)?(矛盾|冲突)|并无(分歧|矛盾)|不矛盾|并无冲突");

    /**
     * 真冲突的写法。<b>出现它就不做上面的丢弃</b>。
     * <p>
     * <b>「不同」这个词收不得</b>，虽然它看着最像：模型描述一件其实一致的事时，
     * 恰恰爱说「表述方式不同」「写法不同」——线上两条误报各含一个「不同」，
     * 把它们收进来等于白做。留下的是「不一致」「不相同」这类<b>指向比对的否定词</b>，
     * 它们几乎只出现在真的对不上时。
     * <p>
     * 判据到这里就收窄到头了，「适用范围一致、但剂量上限不同」这种写法拦不住。
     * 兜住它的是 {@link #NEEDS_HUMAN}：prompt 要求每条冲突都写明「以哪一份为准需人工确认」，
     * 合规写出来的真冲突都会在那里被拦下。两道判据分工不同，缺一个就漏。
     */
    private static final Pattern DISAGREES = Pattern.compile(
            "不一致|不相同|不一样|互相矛盾|存在(实质)?(矛盾|冲突)");

    /**
     * 真冲突一定会把定夺权交回给人。<b>出现它同样不做丢弃</b>——
     * 万一模型在同一段里既说「一致」又请人来定夺，那是它自己没写清楚，
     * 此时宁可多给用户一张卡片，也不能替他把一处真实分歧吞掉。
     */
    private static final Pattern NEEDS_HUMAN = Pattern.compile("需人工确认|无法判定|请人工|人工核对|无法确定先后");

    /**
     * @param refs   这条冲突涉及的证据编号（1 基，对应回答里 {@code [n]} 的 n），可能为空
     * @param detail 冲突原文，形如「条目 1 与条目 3：条目 1 称上限 2000IU，条目 3 称 4000IU」
     */
    /**
     * @param refs     涉及的证据编号（1 基），可能为空——认不出编号时不猜，只展示文字
     * @param detail   冲突原文
     * @param resolved 能否依据版本信息定夺：{@code true} 表示已按较新版本给出结论，
     *                 {@code false} 表示无法判定先后、必须人工确认。
     *                 <b>与 detail 里的文字是两个层次</b>：文字是给用户看的说明，
     *                 这个布尔是给前端决定卡片形态的——「已定夺」是一张普通提示，
     *                 「待人工确认」才需要用户真的去看原文，两者的视觉权重不该一样。
     */
    public record Conflict(List<Integer> refs, String detail, boolean resolved) {
    }

    /**
     * @param reply     抽掉冲突段之后的答案；没有冲突时与输入逐字相同
     * @param conflicts 结构化后的冲突，按出现顺序
     */
    public record Report(String reply, List<Conflict> conflicts) {
    }

    /**
     * @param reply         模型给出的完整答案
     * @param evidenceCount 本轮证据条数，用来丢弃指向不存在条目的编号
     */
    public static Report extract(String reply, int evidenceCount) {
        if (reply == null || reply.isBlank()) {
            return new Report(reply, List.of());
        }

        List<int[]> blocks = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();

        Matcher head = HEAD.matcher(reply);
        while (head.find()) {
            // 连排的多个标记会被首个块整块吞掉（见 endOfBlock），后一个标记落在已消费的
            // 区间里，跳过即可——否则同一段文字会被抽两次
            if (!blocks.isEmpty() && head.start() < blocks.get(blocks.size() - 1)[1]) {
                continue;
            }
            int blockEnd = endOfBlock(reply, head.end());
            blocks.add(new int[] {head.start(), blockEnd});

            String body = reply.substring(head.end(), blockEnd).strip();
            // 后续行各自也可能以标记开头（模型逐条列冲突时会重复写），去掉重复的标记再拼
            body = body.replace(MARKER, " ").replaceAll("\\s+", " ").strip();
            if (body.isEmpty() || selfResolved(body)) {
                // 自陈一致的那段照删不误：它既不该变成卡片，也不该留在正文里——
                // 用户在答案里要的是结论，不是一次并无分歧的核对过程
                continue;
            }
// 依据「模型有没有把定夺权交回给人」来判断 resolved，而不是自己重解析版本号：
            // 版本比较涉及单位、适用范围、并列款之间的语义，规则判不准；模型已经在文字里
            // 给出了结论，抽取层只需读出「它到底定了没有」
            conflicts.add(new Conflict(refs(body, evidenceCount), body, !needsHuman(body)));
        }

        if (blocks.isEmpty()) {
            return new Report(reply, List.of());
        }
        return new Report(TextRanges.delete(reply, blocks), List.copyOf(conflicts));
    }

    /**
     * 冲突段的结束位置：吃掉后续非空行，直到空行或文末。
     * <p>
     * 按「段落」而不是「一行」来定边界，是因为模型几乎不会把一条冲突写在一行里——
     * 更像这样：
     * <pre>
     * 【冲突】关于每日上限，两份资料说法不一致：
     * - 条目 1 称 2000IU
     * - 条目 3 称 4000IU
     * </pre>
     * 只吃一行的话，下面两行会被 {@link CitationVerifier} 当成两句没有出处的断言剔除掉
     * ——冲突的细节反而在答案里消失，只剩一个光秃秃的标题。
     */
    private static int endOfBlock(String text, int from) {
        int firstLineEnd = text.indexOf('\n', from);
        if (firstLineEnd < 0) {
            return text.length(); // 标记行就是文末
        }
        int cursor = firstLineEnd + 1;
        while (cursor < text.length()) {
            int lineEnd = text.indexOf('\n', cursor);
            String line = lineEnd < 0 ? text.substring(cursor) : text.substring(cursor, lineEnd);
            if (line.isBlank()) {
                // 连这个空行一起吞掉，否则删完会留下它拼出的三连换行
                return lineEnd < 0 ? text.length() : lineEnd + 1;
            }
            if (lineEnd < 0) {
                return text.length();
            }
            cursor = lineEnd + 1;
        }
        return text.length();
    }

    /**
     * 模型是否已在这段里自己给出了「核对下来一致」的结论。
     * <p>
     * 三个条件缺一不可：说出了「一致」，没同时说「不一致」，也没有把定夺权交回给人。
     * 后两条是护栏——它们任一在场都说明<b>模型自己没把话说死</b>，此时不替它收，
     * 让人看到卡片自己去核对。
     */
    /**
     * 这条冲突是不是「无法定夺、必须人工确认」。
     * <p>
     * <b>为什么不能直接复用 {@link #NEEDS_HUMAN}</b>：那个正则里含「以哪一份为准」，
     * 它是为**旧**提示词（每条冲突都必须写「以哪一份为准需人工确认」）设计的。
     * 现在提示词分了两条路：「以条目 3 为准」是**已定夺**，只有「需人工确认」
     * 「无法判定先后」这类才是待人工。用旧正则判会把定夺成功的也标成待人工——
     * 于是所有冲突卡片都变成同一种形态，而这次改造的全部意义就是让两者分开。
     * <p>
     * 单独一个方法而不是再塞一个正则进 {@code selfResolved}：那个方法回答的是
     * 「这段要不要整段丢掉」（自陈一致才丢），与「这条冲突定了没有」是两个问题。
     */
    private static boolean needsHuman(String body) {
        return NEEDS_HUMAN.matcher(body).find();
    }

    private static boolean selfResolved(String body) {
        if (NEEDS_HUMAN.matcher(body).find() || DISAGREES.matcher(body).find()) {
            return false;
        }
        return CONSISTENT.matcher(body).find();
    }

    private static List<Integer> refs(String body, int evidenceCount) {
        TreeSet<Integer> found = new TreeSet<>();
        Matcher matcher = REF.matcher(body);
        while (matcher.find()) {
            for (int group = 1; group <= 3; group++) {
                String value = matcher.group(group);
                if (value == null) {
                    continue;
                }
                int no = Integer.parseInt(value);
                // 指向不存在条目的编号直接丢弃：它是模型抄错的，不是证据
                if (no >= 1 && (evidenceCount <= 0 || no <= evidenceCount)) {
                    found.add(no);
                }
            }
        }
        return List.copyOf(found);
    }
}
