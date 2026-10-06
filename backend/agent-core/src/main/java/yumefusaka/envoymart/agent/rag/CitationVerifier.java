package yumefusaka.envoymart.agent.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
     * 书名号形式的出处：{@code 《文档名》}。
     * <p>
     * 上限 60 字符：真实文档标题不会有更长的，防止把「《甲》和《乙》之间那段话」
     * 这类带书名号的叙述整段吞成一个标题。
     */
    private static final Pattern TITLE_CITATION = Pattern.compile("《([^》]{1,60})》");

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
     * 工具返回值参与「命中出处」判定的最小长度。
     * <p>
     * 工具返回里会出现「元」「袋」这类一两个字的碎片，拿它们做包含匹配，
     * 几乎每一句都能命中——那道闸就形同虚设。商品名与编号没有这么短的。
     */
    private static final int MIN_TOOL_STRING_LENGTH = 3;

    /**
     * 出现这些词，说明这句话在陈述一条规则、标准或时效，而不是随口一说。
     * <p>
     * <b>数字单列</b>：金额、天数、浓度、上限——事实性断言绝大多数由它携带，
     * 而它也是编造代价最高的那一类。
     */
    private static final Pattern FACT_SIGNAL = Pattern.compile(
            "\\d|规定|政策|条款|标准|上限|下限|禁止|不得|必须|应当|期限|时效|有效期");

    /**
     * 复述用户问题的引导句（「你问的是…的规定。」）。
     * <p>
     * 它命中了「规定」，长度也够，但这不是一条断言——这句话里唯一的事实就是用户
     * 刚刚说过的话。没有可标的出处，也<b>不该有</b>：给它标一个引用，
     * 等于说「用户问的问题」是平台文档规定的。
     */
    private static final Pattern ECHO_LEAD = Pattern.compile(
            "^[\\s>*\\-•#\\d.、)）]*(你|您)(问的|的问题是|想了解的是|咨询的|提到)");

    /**
     * 陈述「知识库里没有」的句子（「知识库中未检索到…条款」）。
     * <p>
     * 这类句子的依据恰恰是<b>检索结果为空</b>这件事本身，没有可标的出处。剔掉它，
     * 拒答回答里最关键的那句就没了：用户看到规则罗列，却看不到「这些规则都不管你问的事」。
     * <p>
     * 判据刻意窄：必须同时出现「缺失」与「规则类名词」。注意词表里<b>没有「不」</b>——
     * 「本品不适用于孕妇」是一句真断言，不能放它走；「未/没有/无」才是"不存在"。
     */
    private static final Pattern ABSENCE_SIGNAL = Pattern.compile(
            "(未|没有|无|暂无|不存在|查不到|未查到|未见)[^，。！？；\\n]{0,40}"
                    + "(规定|政策|条款|细则|要求|标准|资料|内容|说明|记录|条目|依据|收录|覆盖|涉及)");

    /**
     * 违规句占比超过它就整体放弃剔除。
     * <p>
     * 三分之一：低于这个比例更像「个别句子漏标」，高于它更像「整篇没按格式来」。
     */
    private static final double MAX_VIOLATION_RATIO = 1.0 / 3.0;

    /** 违规句数不超过它时永远剔除，不看占比——三句话的回答里 1/3 是个过苛的尺子 */
    private static final int ALWAYS_STRIP_UP_TO = 3;

    /**
     * 「这句话在陈述关于本平台的事实」的判据 —— <b>比 {@link #FACT_SIGNAL} 宽，且必须宽</b>。
     * <p>
     * <b>它回答的是「整篇该不该挂无依据横幅」，不是「这句话该不该有引用」。</b>
     * 这两件事此前被同一根尺子量着（{@code needsCitation}），于是纯寒暄轮
     * （无引用、无工具）也满足「整篇无依据」，界面上给「你好」挂一条橙色警示。
     * <p>
     * <b>为什么不干脆把横幅的判据收紧成「至少有一句被 needsCitation 判为断言」。</b>
     * 那会开一个静默的幻觉口子：建模出来的「平台支持七天无理由退货」既没有数字，
     * 也不含 {@link #FACT_SIGNAL} 里的任何一个词，{@code needsCitation} 返回 false——
     * 于是整段编造的政策条款不再被标注。<b>用一条可见但无害的提示换一个静默的幻觉漏洞，
     * 是错的交易。</b>
     * <p>
     * 所以这里另立一把<b>更宽</b>的尺子：锚点是<b>领域对象与领域谓词</b>——
     * 「平台 / 商品 / 订单 / 政策 / 会员 / 退货 / 支持 / 可以……」出现任一，
     * 就说明这段话在讲本平台的事，值得被依据支撑。它不判「有没有出处」，
     * 只判「有没有值得有出处的内容」。
     * <p>
     * <b>边界是刻意倒向「宁可多标」的，但「助手在描述自己」不在此列。</b>
     * 判错的两个方向代价不对称：多标一条横幅，用户看到的是一句略重的提醒；
     * 漏标一段编造的政策，用户会把模型编的条款当成平台的承诺。
     * <p>
     * 不过「我是你的电商助手，可以帮你查订单」这类自我介绍要排除掉——它的领域名词
     * （订单 / 物流 / 售后）说的是<b>助手能做什么</b>，不是<b>平台有什么规则</b>，
     * 把它算作平台陈述会让最常见的寒暄轮照挂横幅（第一版就是这样，U39 没修好）。
     * 排除规则见 {@link #SELF_INTRO_LEAD} 与 {@link #RULE_PREDICATE}：
     * 前者认出「助手自我描述」，后者保证真正的规则断言不被误排除。
     */
    private static final Pattern PLATFORM_CLAIM = Pattern.compile(
            "平台|本店|本平台|商品|产品|订单|物流|发货|签收|配送|快递|退货|退款|换货|售后|保修|维修|"
                    + "发票|开票|优惠券|折扣|满减|促销|活动|会员|积分|等级|权益|"
                    + "政策|规定|条款|规则|细则|标准|流程|时限|期限|时效|有效期|"
                    + "运费|邮费|价格|售价|库存|现货|保质期|成分|配方|剂量|用量|功效|适应症|禁忌|"
                    + "孕妇|儿童|老年人|患者|服用|同服|禁用|"
                    // 领域谓词：这些词单独出现不足以说明「在讲平台的事」——
                    // 「有什么可以帮你的吗」里的「可以」就是反例——所以它们成组出现，
                    // 或者与上面的领域名词共现（见 GROUPED_CLAIM）
                    + "支持|需要|应当|允许|禁止|不得|建议|适用于|受理");

    /**
     * 「领域谓词 + 领域对象」的组合式判据。
     * <p>
     * <b>为什么谓词不能单独算数。</b>「可以」「支持」在通用口语里太常见——
     * 「你好！有什么可以帮你的吗？」里的「可以」与平台政策毫无关系，
     * 把它当领域信号会让最常见的寒暄句照样挂上横幅，等于没修。
     * <p>
     * <b>但不能反过来只认名词。</b>实测里模型最典型的越界形态是：先声明
     * 「不在平台支持范围内」，再补一段通用知识（「蓝牙耳机连接手机时，先长按功能键进入
     * 配对模式」）——那段话里可能一个领域名词都没有，却是彻头彻尾编的。
     * 只认名词会放过它，而它恰恰是这道闸唯一必须拦住的场景。
     * <p>
     * 折中是<b>句式信号</b>：出现「可以/支持/需要/应当……」这类<b>给用户下结论的谓词</b>，
     * 并且句子本身够长（不是寒暄），就算在陈述事实。这条判据比 {@link #FACT_SIGNAL} 窄一点
     * （不认数字），但比「只认领域名词」宽得多。
     */
    private static final Pattern DOMAIN_PREDICATE = Pattern.compile(
            "支持|可以|需要|应当|允许|禁止|不得|建议|适用于|受理|包含|提供|享受|获得|属于|视为");

    /**
     * 谓词句的最小长度 —— 低于它更可能是寒暄（「可以帮你」「随时可以」）。
     * <p>
     * 与 {@link #FACT_MIN_LENGTH} 同一个数、同一个理由：中文里十来个字以内很难承载
     * 一条完整的事实陈述。
     */
    private static final int CLAIM_MIN_LENGTH = 12;

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
        return verify(reply, evidenceCount, hasToolEvidence, Set.of());
    }

    /**
     * 带「可引用标题」的完整版。
     * <p>
     * 本项目的出处有两种写法：system prompt 证据用编号角标 {@code [n]}，
     * 工具返回的知识（{@code interaction_check}、{@code knowledge_search}）用《文档名》——
     * 后者没有编号可标：它的内容不在 prompt 里，编号空间只属于 prompt 证据。
     * 不认这种写法，工具检索到的事实句会被当成「讲事实没出处」剔除，
     * 而这正是 {@link #MAX_VIOLATION_RATIO} 最不该误伤的东西：内容有真出处，
     * 只是出处不是编号。
     * <p>
     * <b>只认「平台声明过的标题」</b>（{@code citableTitles}：证据切片的标题与位置、
     * 工具输出中「出处：」行里的书名号）。不设这道限，模型随手编一个《XX 规范》
     * 就能把任何编造句洗成有出处——识别面越宽，闸门越等于没有。
     * 标题比对忽略空白差异（模型会漏掉「维生素 D3」里的空格），其余逐字。
     */
    public static Verdict verify(String reply, int evidenceCount, boolean hasToolEvidence,
                                 Set<String> citableTitles) {
        return verify(reply, evidenceCount, hasToolEvidence, citableTitles, null);
    }

    /**
     * 带用户消息的 5 参版本，转发到带工具值的完整版。
     * <p>
     * 保留它是为了不动已有调用方：商品实体是后补的一条判据，
     * 而评测与离线调用那边没有工具执行记录，走这条路即可。
     */
    public static Verdict verify(String reply, int evidenceCount, boolean hasToolEvidence,
                                 Set<String> citableTitles, String userMessage) {
        return verify(reply, evidenceCount, hasToolEvidence, citableTitles, userMessage, null);
    }

    /**
     * 带「用户本轮问了什么」与「工具返回过哪些值」的完整版 —— <b>U39 真正收口的那一版</b>。
     * <p>
     * <b>toolStrings 是 P0-A 的商品误剔除修的那一条。</b>商品表的每一个字都来自
     * <p>
     * <b>为什么光看回答收不掉寒暄横幅。</b>「回答里有没有关于平台的陈述」这条判据，
     * 在词表层面无法与「助手自我介绍」分开：模型对「你好」返回的是
     * 「我是平台的智能电商助手，可以帮你：- 查订单 / 查物流 / 售后咨询 …」，
     * 领域名词（平台 / 订单 / 物流 / 售后）一个不少，它确实在讲平台能做什么。
     * 要按措辞逐句排除，就得为每一轮新出现的自我介绍模板再加一条规则——
     * 那是补丁链，不是判据（实测连加四条排除规则，仍被「- **找商品**：…
     * 比如「孕妇能吃的钙片，200 以内」」的数字绕过）。
     * <p>
     * <b>换一个语义正确的锚点：横幅说的是「这一轮没有平台依据」，而「这一轮」包含用户的问题。</b>
     * 用户问「你好」，这一轮根本不在问平台的事，模型答一段自我介绍天经地义——
     * 它没有任何需要被知识库支撑的内容。反过来，用户问「你们平台退货政策是什么」，
     * 模型给一段没有出处的回答，那才是必须挂横幅的场景。<b>判据从「模型说了什么」
     * 移到「用户在问什么」，前者是开放集（措辞无穷），后者是封闭集（问法有限）。</b>
     * <p>
     * 两个条件都要满足才挂横幅：回答里有平台陈述 <b>且</b> 用户问的是平台的事。
     * 这不会开口子——反例「平台支持七天无理由退货」在无引用时仍然被标（用户问的就是退货政策，
     * {@code asksAboutPlatform} 为真）。它只在「用户没问平台的事」时收回那条提示。
     * <p>
     * <b>已知代价</b>：用户闲聊、模型却主动编一条平台政策时，不再挂横幅。这条路径比
     * 「每轮寒暄都误报」罕见得多，且寒暄误报是<b>每轮都发生</b>的。两者不可交换。
     *
     * @param userMessage 用户本轮的原话；为 null 时退化为不设意图门槛
     *                    （评测与离线调用没有用户消息，保持与历史一致）
     * @param toolStrings 本轮工具返回过的字符串（商品编号与名称等）。
     *                    与 citableTitles 同一层：都是平台自己声明过的值，只是形态不同。
     *                    为 null 表示调用方没提供，退化成只看 [n] 与书名号。
     */
    public static Verdict verify(String reply, int evidenceCount, boolean hasToolEvidence,
                                 Set<String> citableTitles, String userMessage,
                                 Set<String> toolStrings) {
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

            if (hasValidCitation(sanitized, evidenceCount, citableTitles, toolStrings)) {
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
        // 整篇无依据 —— 除「没有引用、没有工具记录」之外，还得「确实有值得被支撑的内容」。
        //
        // 后半句是补上来的：纯寒暄轮（「你好！有什么可以帮你的吗？」）同样没有引用、
        // 没有工具，但它没有任何关于平台的陈述，挂一条「无平台依据」的橙色横幅是误报。
        // 判据见 PLATFORM_CLAIM —— 它比 needsCitation 宽得多，专门用来兜住
        // 「平台支持七天无理由退货」这类没有数字也没有信号词、却是彻头彻尾编造的句子。
        //
        // 第三句（asksAboutPlatform）是 U39 端到端实测后补的：只看回答收不掉寒暄横幅——
        // 模型对「你好」会返回带能力清单的自我介绍，领域名词一个不少，词表层面无法与
        // 真正的平台陈述分开（见 verify 的重载说明）。用户没问平台的事，就不该说
        // 「这轮没有平台依据」。userMessage 为 null（评测/离线）时不做这道门槛。
        boolean platformQuestion = userMessage == null || asksAboutPlatform(userMessage);
        boolean ungrounded = !knowledgeAnswer && !hasToolEvidence
                && containsPlatformClaim(reply) && platformQuestion;
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

    private static boolean hasValidCitation(String sentence, int evidenceCount, Set<String> citableTitles,
                                             Set<String> toolStrings) {
        Matcher refs = CITATION.matcher(sentence);
        while (refs.find()) {
            int no = Integer.parseInt(refs.group(1));
            if (no >= 1 && no <= evidenceCount) {
                return true;
            }
        }
        if (!citableTitles.isEmpty()) {
            Matcher titles = TITLE_CITATION.matcher(sentence);
            while (titles.find()) {
                if (citableTitles.contains(normalizeTitle(titles.group(1)))) {
                    return true;
                }
            }
        }
        return mentionsToolString(sentence, toolStrings);
    }

    /**
     * 这句话有没有点名「工具本轮真的返回过」的一个值（商品编号或商品名）。
     * <p>
     * <b>为什么商品要有这一条。</b>商品表的出处是 {@code product_search} 的返回，
     * 它既没有 {@code [n]} 角标（编号空间只属于 prompt 证据），也不在「出处：」行的书名号里。
     * 不认它，一张完全正确的商品表会被判成「讲事实没出处」逐行删掉，用户看到只剩表头的空表格。
     * <p>
     * <b>识别面收窄到「工具真的返回过」。</b>判据是逐字包含（忽略空白），不做模糊相似：
     * 集合里装的是 {@code ToolExecution.entities} 声明的值，模型编一个相近的名字仍然过不了。
     * <p>
     * <b>长度门槛。</b>工具返回里可能有「元」「袋」这类一两个字的碎片，
     * 用它们做包含匹配几乎每句都能命中。低于 3 个字的值一律不参与——
     * 商品名与编号没有这么短的。
     */
    private static boolean mentionsToolString(String sentence, Set<String> toolStrings) {
        if (toolStrings == null || toolStrings.isEmpty()) {
            return false;
        }
        String compact = normalizeTitle(sentence);

        for (String value : toolStrings) {
            if (value == null || value.codePointCount(0, value.length()) < MIN_TOOL_STRING_LENGTH) {
                continue;
            }
            if (compact.contains(normalizeTitle(value))) {
                return true;
            }
        }
        return false;
    }
    /**
     * 从证据切片的标题/位置里收集可引用标题 —— 平台自己声明过的出处。
     * <p>
     * 位置串形如 {@code 《维生素 D3 说明书》 > 第二章 > 3.2}，标题本身可能不带书名号，
     * 两种形态都收：模型引用时可能带位置也可能只写文档名。
     */
    public static void collectTitles(Set<String> into, String title, String position) {
        if (title != null && !title.isBlank()) {
            into.add(normalizeTitle(title));
        }
        if (position != null) {
            Matcher m = TITLE_CITATION.matcher(position);
            while (m.find()) {
                into.add(normalizeTitle(m.group(1)));
            }
        }
    }

    /**
     * 正文里写到的全部《文档名》（已归一化）。
     * <p>
     * 只做提取，不判断可信：调用方拿去和 {@link #collectTitles} 收出来的可引用集合取交集
     * ——「正文提到了哪些书名」与「平台声明过哪些出处」是两件事，混在一起就又回到了
     * 把语料里任意书名当成出处的老问题。
     */
    public static Set<String> titlesIn(String text) {
        Set<String> titles = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return titles;
        }
        Matcher m = TITLE_CITATION.matcher(text);
        while (m.find()) {
            titles.add(normalizeTitle(m.group(1)));
        }
        return titles;
    }

    /**
     * 把正文里「有编号可指」的《文档名》换回 {@code [n]}。
     * <p>
     * <b>为什么这件事必须由机器做。</b>模型手上有两套出处写法：system prompt 的证据用
     * {@code [n]}，工具检索回来的内容用《文档名》。同一个文档在两边都出现时，它会按
     * <b>文档</b>分而不是按<b>来源</b>分，把整篇（包括 prompt 证据）统一写成书名号——
     * 实测正是如此。而编号是界面上点回原文的唯一入口，书名号渲染不出角标，
     * 整条溯源链路就断了。两边提示词都已写明「各管各的」，但提示词是请求不是保证：
     * 查它有没有照做、并把入口还回去，是校验层该干的活。
     * <p>
     * <b>同一份文档的多个切片取最靠前的那一条。</b>本项目 47 篇文档切成 408 片，
     * 多切片是常态。模型写《文档名》时本来就没指明是哪个切片，指向该文档最相关
     * （即排在前面）的那条，不比一个不能点的书名号更错，而它让用户有得点。
     * <p>
     * 不在证据里的《X》原样保留：那是工具输出里平台声明过的另一个出处
     * （见 {@link #collectToolTitles}），它有出处但没编号，{@link #hasValidCitation} 认它。
     * <p>
     * 必须跑在 {@link ConflictReporter#extract} <b>之前</b>：冲突段里的「哪几条对不上」
     * 只认编号与「条目 n」，先归一化，抽取器才认得出这条冲突指的是哪几条证据。
     */
    public static String numberTitles(String reply, List<DocumentChunk> evidence) {
        if (reply == null || reply.isBlank() || evidence == null || evidence.isEmpty()) {
            return reply;
        }
        Map<String, Integer> byTitle = new HashMap<>();
        for (int i = 0; i < evidence.size(); i++) {
            Set<String> titles = new LinkedHashSet<>();
            DocumentChunk chunk = evidence.get(i);
            collectTitles(titles, chunk.getTitle(), chunk.getPosition());
            for (String title : titles) {
                byTitle.putIfAbsent(title, i + 1);
            }
        }
        if (byTitle.isEmpty()) {
            return reply;
        }
        Matcher m = TITLE_CITATION.matcher(reply);
        StringBuilder sb = new StringBuilder(reply.length());
        while (m.find()) {
            Integer no = byTitle.get(normalizeTitle(m.group(1)));
            m.appendReplacement(sb, Matcher.quoteReplacement(no == null ? m.group() : "[" + no + "]"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 从工具输出里收集可引用标题 —— 只认「出处：」行，且只看到引文开始为止。
     * <p>
     * 不能全篇扫《…》：检索回来的原文引用里本来就带着《消费者权益保护法》这类书名，
     * 全篇扫描等于把语料里的任意书名都升级成「平台声明过的出处」。
     * 「出处：」是工具渲染时自己写的行首标记，只出现在平台声明出处的地方。
     * <p>
     * <b>行内还要再切一刀</b>：渲染格式是 {@code 出处：《文档名》｜原文：「引用文本」}，
     * 引用文本与出处<b>同一行</b>。只按「行含出处」扫描，引文里提到的书名会一起被采信
     * （实测过：图谱边的 quote 引用《消费者权益保护法》条款，它就被洗成了合法出处）。
     * 引文一定以左引号开头，所以扫到第一个左引号为止即可，不必枚举「原文／引文／片段」这类标签。
     */
    public static void collectToolTitles(Set<String> into, String toolOutput) {
        if (toolOutput == null || toolOutput.isBlank()) {
            return;
        }
        for (String line : toolOutput.split("\n")) {
            int marker = line.indexOf("出处：");
            if (marker < 0) {
                continue;
            }
            String head = line.substring(0, quoteStart(line, marker + "出处：".length()));
            Matcher m = TITLE_CITATION.matcher(head);
            while (m.find()) {
                into.add(normalizeTitle(m.group(1)));
            }
        }
    }

    /** 从 {@code from} 起第一个左引号的下标；没有引号就返回行尾（整行都是出处） */
    private static int quoteStart(String line, int from) {
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '「' || c == '“' || c == '"') {
                return i;
            }
        }
        return line.length();
    }

    /** 标题比对忽略空白：模型会漏掉「维生素 D3」里的空格，其余逐字 */
    public static String normalizeTitle(String title) {
        return title.replaceAll("\\s+", "");
    }

    /**
     * 这句话是否在陈述一条需要出处的事实。
     * <p>
     * 三条同时成立才算：够长（不是寒暄）、带着事实信号（数字或规范性词）、
     * 且不是在说「关于对话或检索本身」的元陈述。
     * <p>
     * 后一条是两次真实误伤换来的：模型答「知识库中未检索到宠物食品召回条款」时，
     * 前半句「你问的是…的规定」与后半句「未检索到…」都被判成「讲事实却没出处」，
     * 一起从回答里删掉了——而这两句恰恰是整段回答的骨架，剩下的规则罗列反而
     * 读不出结论。判据见 {@link #ECHO_LEAD} 与 {@link #ABSENCE_SIGNAL}。
     */
    private static boolean needsCitation(String sentence) {
        String trimmed = sentence.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.codePointCount(0, trimmed.length()) < FACT_MIN_LENGTH) {
            return false;
        }
        if (ECHO_LEAD.matcher(trimmed).find() || ABSENCE_SIGNAL.matcher(trimmed).find()) {
            return false;
        }
        return FACT_SIGNAL.matcher(trimmed).find();
    }

    /**
     * 整篇里有没有「关于本平台的事实陈述」—— 决定无依据横幅该不该挂。
     * <p>
     * 判据是<b>领域词汇</b>而不是语气、长度或句式：一段话有没有在讲平台的事，
     * 与它说话的方式无关。判据刻意宽（见 {@link #PLATFORM_CLAIM}），
     * 因为漏标的代价是用户把编造的条款当成平台承诺。
     */
    static boolean containsPlatformClaim(String reply) {
        if (reply == null || reply.isBlank()) {
            return false;
        }
        if (PLATFORM_CLAIM.matcher(reply).find()) {
            return true;
        }
        // 领域名词一个都没出现时，再看有没有「够长的断言句」。两道补充判据各兜一类：
        //
        // ① 谓词句（DOMAIN_PREDICATE）——「先声明不在平台支持范围内，再补一段通用建议」
        //    这个实测形态。它可能一个领域名词都没有，却在对用户下结论。
        // ② 事实信号（FACT_SIGNAL）——「长按功能键 3 秒后指示灯闪烁」这类通用操作说明。
        //    它连谓词都没有，但它们同样是模型凭空写的、用户可能照着做的内容。
        //
        // 只认领域名词是**不够的**：模型最典型的越界恰恰是「平台没覆盖 → 拿通用知识顶上」，
        // 而那段通用知识里可以完全没有平台词汇。判据宁可宽——
        // 多挂一条无害的提示，远比放过一段编造的内容好。
        for (String sentence : reply.split("[。！？!?；;\\n]+")) {
            String trimmed = sentence.strip();
            if (trimmed.codePointCount(0, trimmed.length()) < CLAIM_MIN_LENGTH) {
                continue;
            }
            if (DOMAIN_PREDICATE.matcher(trimmed).find() || FACT_SIGNAL.matcher(trimmed).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 纯寒暄/致谢/确认 —— 用户这一轮没有问任何平台的事。
     * <p>
     * <b>为什么用一份显式清单而不是「不含领域词就算寒暄」。</b>反过来写会开口子：
     * 用户问「你觉得这个东西怎么样」（不含领域词，却在问商品）会被判成寒暄，
     * 模型那段编造的「该商品适合孕妇服用」就没人拦了。这里只在<b>明确认出是纯寒暄</b>
     * 时才收回提示，其余一律按「问了平台的事」处理——边界继续倒向宁可多标。
     * <p>
     * 覆盖的是最常见的几种：问候、致谢、确认、告别，以及「你能做什么」这类能力询问
     * （它问的是助手能力，答的自我介绍也不需要知识库依据）。
     */
    private static final Pattern PURE_CHITCHAT = Pattern.compile(
            "^(你好|您好|哈喽|嗨|hi|hello|在吗|在不在|早上好|中午好|晚上好|早安|晚安|"
                    + "谢谢|多谢|感谢|thanks|thank you|3q|"
                    + "好的|好|嗯|哦|ok|OK|可以|行|收到|明白|知道了|了解|"
                    + "再见|拜拜|bye|"
                    + "你是谁|你叫什么|介绍一下你|你能做什么|你会什么|你能帮我做什么|你有什么功能|你有哪些功能)"
                    + "[。！？!?~～，,\\s]*$");

    /**
     * 与本站业务无关的话题 —— 同样不是「在问平台的事」。
     * <p>
     * <b>为什么要单列一条，而不是塞进 {@link #PURE_CHITCHAT}。</b>那张表覆盖的是
     * 「纯寒暄」，用整句锚定（{@code ^...$}）；而「今天天气怎么样」「现在几点」
     * 是一句<b>提问</b>，句式千变万化，钉不住整句。它们与寒暄的共同点是：答案只能
     * 来自站外知识，平台无论如何都答不了——模型回一句「我查不了天气」并附上能力介绍，
     * 是<b>正确</b>行为，不该被判成「这一轮没有平台依据」。
     * <p>
     * 判据是<b>话题域词 + 无平台领域词</b>：出现明确属于站外世界的主题，且整句不含
     * 任何 {@link #PLATFORM_CLAIM} 里的领域词，才算站外提问。第二个条件保证
     * 「天气这么热，这个保健品需要冷藏吗」这类混合提问仍按平台问题处理。
     * <p>
     * 边界继续倒向「宁可多标」：只有词表命中才收回横幅；没覆盖到的新话题仍按
     * 「问了平台的事」处理（多一条无害提示）。这与 {@link #asksAboutPlatform}
     * 的白名单取向一致——漏认一句站外提问的代价是提示偏重（可见、无害），
     * 漏认一句商品咨询的代价是编造内容被放过（静默、有害）。
     */
    private static final Pattern OFF_TOPIC = Pattern.compile(
            "天气|气温|下雨|晴天|阴天|台风|雾霾|空气质量|"
                    + "几点|现在时间|今天几号|星期几|节假日|放假安排|"
                    + "讲个笑话|讲笑话|唱首歌|写首诗|写代码|翻译一下|"
                    + "股票|基金|彩票|汇率|油价|"
                    + "新闻|比赛|比分|电影|电视剧|游戏攻略");

    /**
     * 用户这一轮问的是不是平台的事 —— 无依据横幅的<b>意图门槛</b>。
     * <p>
     * <b>白名单式判据：只有明确认出是纯寒暄，才收回横幅；其余一律按「问了平台的事」处理。</b>
     * <p>
     * 不写成「含领域词才算提问」——那要求词表覆盖用户所有问法。实测缺口立刻出现：
     * 「维生素 D3 每天吃多少？」里没有任何一个领域名词（「维生素」不在
     * {@link #PLATFORM_CLAIM} 表里），却是一句典型的商品咨询，模型编一个剂量出来
     * 就是幻觉。领域词表是为<b>回答侧</b>设计的（回答里出现领域名词说明在讲平台规则），
     * 拿它去卡<b>提问侧</b>必然漏。反过来写就绕开了覆盖度问题：漏认一句寒暄的后果
     * 是「该收的横幅没收」（可见、无害），漏认一句咨询的后果是「编造内容被放过」
     * （静默、有害）——两者不可交换。
     */
    static boolean asksAboutPlatform(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String question = userMessage.strip();
        if (PURE_CHITCHAT.matcher(question).find()) {
            return false;
        }
        // 站外话题：必须整句没有任何平台领域词才算。反向条件保证了
        // 「天气热了，这个保健品需要冷藏吗」这类混合提问仍按平台问题处理。
        if (OFF_TOPIC.matcher(question).find() && !PLATFORM_CLAIM.matcher(question).find()) {
            return false;
        }
        return true;
    }
}