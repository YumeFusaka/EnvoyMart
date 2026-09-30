package yumefusaka.envoymart.agent.rag;

import yumefusaka.envoymart.agent.llm.ToolExecution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具事实一致性核对：<b>回答里讲的业务事实，与工具当场返回的事实逐条对。</b>
 * <p>
 * 引用校验管的是「这句话有没有出处」，管不了「这句话说的是不是真的」。订单类问题走的是
 * 工具而不是知识库，模型手上明明有《应付金额 ¥128.00》，写成「¥182.00」照样有工具轨迹、
 * 照样通得过所有引用检查——用户照着这个数字去对账，对不上。幻觉在这里的成本不是
 * 「讲了个没出处的话」，是「把用户的订单金额说错了」。
 * <p>
 * <b>判据来自工具自己声明的事实</b>（{@code ToolExecution.facts}），不是从工具输出文本里
 * 反解。工具是唯一知道自己返回了什么的地方，让它顺手声明一遍，比让核对器去猜
 * 「应付金额：¥128.00」这行文本的格式要稳——输出文案改一个字，解析它的正则就悄悄失效，
 * 而失效的核对器看起来和通过是一模一样的。
 * <p>
 * <b>两条刻意的取舍</b>，都指向同一件事：宁可漏，不可误伤。
 * <ul>
 *   <li><b>标签要无歧义。</b>声明「订单状态」而不是「状态」——后者会撞上「物流状态」
 *       「支付状态」，把一句完全正确的话判成矛盾并从回答里删掉。为这个，
 *       工具声明标签时要挑一个只可能指它自己的说法。</li>
 *   <li><b>只核对模型主动用到的标签。</b>标签后面的取值才是它下的断言；模型换一套说法
 *       （「订单已经发货了」而不提「订单状态」）时这里认不出来。</li>
 * </ul>
 * 代价是漏检：模型绕开标签说错了，这道闸放行。收益是不会因为「一句话的措辞不在预料内」
 * 就删掉正确内容——误伤的代价比漏检高得多，因为漏检只是少了一层保险，
 * 误伤是平台主动把对的答案改错了。
 */
public final class ToolFactVerifier {

    /** 断句与引用校验共用一套边界，避免两处对「一句」的理解不同 */
    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;\n]+[。！？!?；;\n]*");

    /** 标签之后看多远——够读完「：¥128.00」「是 128 元」这类写法，又不会跨到下一个字段 */
    private static final int WINDOW = 24;

    /**
     * 标签与取值之间的连接成分，形如 {@code 订单状态：已支付}、{@code 订单状态是已支付}、
     * {@code 订单状态改为已发货}。
     * <p>
     * <b>必须要求出现连接成分</b>，否则「订单状态查询」「订单状态的变更记录」这类把标签
     * 当普通词用的地方会全被判成断言。宁可漏掉「订单状态已发货」这种不带连接词的写法，
     * 也不能把「订单状态查询」删掉。
     */
    private static final Pattern CONNECTOR = Pattern.compile(
            "^(?:[\\s：:是为有约大概共＝=\\-—]|已经|现已|现在|目前|改为|变成|更新为|成为"
                    + "|显示为|仍是|仍为|还是|变回|已变)+");

    /** 取值里出现的金额，形如 {@code ¥128.00} / {@code 128.00 元} / {@code 128元} */
    private static final Pattern MONEY = Pattern.compile("(?:¥|￥)\\s*(\\d+(?:\\.\\d{1,2})?)|(\\d+(?:\\.\\d{1,2})?)\\s*元");

    private ToolFactVerifier() {
    }

    /**
     * @param mismatches 与工具事实对不上的句子。空表示这一轮没查出问题——注意它也可能是
     *                   「模型压根没用标签」，见类注释里的上限说明
     */
    public record Verdict(String reply, List<String> mismatches, boolean stripped) {
    }

    public static Verdict verify(String reply, List<ToolExecution> executions) {
        if (reply == null || reply.isBlank() || executions == null || executions.isEmpty()) {
            return new Verdict(reply, List.of(), false);
        }
        Map<String, String> facts = declaredFacts(executions);
        if (facts.isEmpty()) {
            return new Verdict(reply, List.of(), false);
        }

        List<String> mismatches = new ArrayList<>();
        // 区间在这里取：断句的 Matcher 就握在手上，事后按文本回找会误伤重复句
        List<int[]> spans = new ArrayList<>();
        Matcher matcher = SENTENCE.matcher(reply);
        while (matcher.find()) {
            String contradiction = contradiction(matcher.group(), facts);
            if (contradiction != null) {
                mismatches.add(contradiction);
                spans.add(new int[] {matcher.start(), matcher.end()});
            }
        }
        if (mismatches.isEmpty()) {
            return new Verdict(reply, List.of(), false);
        }
        return new Verdict(TextRanges.delete(reply, spans), List.copyOf(mismatches), true);
    }

    /**
     * 本轮工具声明的全部事实。同名标签以<b>最后一条</b>为准——一轮里对同一个订单查了两次，
     * 后一次才是当前状态（下单后立刻查一次、取消后再查一次，就是这种形态）。
     */
    private static Map<String, String> declaredFacts(List<ToolExecution> executions) {
        Map<String, String> facts = new LinkedHashMap<>();
        for (ToolExecution execution : executions) {
            if (!execution.isSuccess() || execution.getFacts() == null) {
                continue;
            }
            execution.getFacts().forEach((label, value) -> {
                if (label != null && !label.isBlank() && value != null && !value.isBlank()) {
                    facts.put(label, value);
                }
            });
        }
        return facts;
    }

    /**
     * 这句话和工具事实对得上吗。对得上返回 {@code null}，对不上返回一句可直接展示给用户的说明。
     * <p>
     * 只认标签的<b>第一次出现</b>：一句话里对同一个字段下了两个相反的断言，那句话本身就该重写，
     * 逐处报只会让用户看到两条几乎一样的提示。而「物流状态」这类误报也是从第二处冒出来的。
     */
    private static String contradiction(String sentence, Map<String, String> facts) {
        for (Map.Entry<String, String> fact : facts.entrySet()) {
            int at = sentence.indexOf(fact.getKey());
            if (at < 0) {
                continue;
            }
            String window = sentence.substring(
                    Math.min(sentence.length(), at + fact.getKey().length()),
                    Math.min(sentence.length(), at + fact.getKey().length() + WINDOW));
            if (contradicts(window, fact.getValue())) {
                return "「%s」工具返回的是 %s，回答里写的是别的值".formatted(fact.getKey(), fact.getValue());
            }
        }
        return null;
    }

    private static boolean contradicts(String window, String actual) {
        return isMoney(actual) ? moneyContradicts(window, actual) : wordContradicts(window, actual);
    }

    /**
     * 金额按「标签后出现的第一个数额」判。
     * <p>
     * 用不着往后找第二个数：{@code 应付金额 ¥128，其中运费 ¥8} 里的第二个数是明细不是反驳，
     * 逐个比会把正当的拆账判成矛盾。第一个数才是它替这个字段报的值。
     * <p>
     * 窗口里没有金额就不算断言（{@code 应付金额以结算页为准}），跳过。
     */
    private static boolean moneyContradicts(String window, String actual) {
        Matcher matcher = MONEY.matcher(window);
        if (!matcher.find()) {
            return false;
        }
        String claimed = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        Long claimedCents = toCents(claimed);
        Long actualCents = toCents(actual);
        // 解析不出来只说明这不是我能判的格式，不等于它错了
        return claimedCents != null && actualCents != null && !claimedCents.equals(actualCents);
    }

    /**
     * 状态一类按「工具给的取值有没有原样出现」判，而不是「取出来的那段等不等于它」：
     * 模型会把取值嵌在句子里（{@code 订单状态已经从待支付变成已支付了}），
     * 硬取一段来比对，等于要求它按字段格式说话。
     */
    private static boolean wordContradicts(String window, String actual) {
        if (window.contains(actual)) {
            // 取值在场，断言与事实一致。放在连接词判定之前：模型把它嵌在句子里
            //（「订单状态已经从待支付变成已支付了」）时，句首并没有连接成分
            return false;
        }
        String rest = CONNECTOR.matcher(window).replaceFirst("");
        if (rest.equals(window)) {
            // 接不上连接成分：标签在这里是当普通词用的（「订单状态查询」），不是断言
            return false;
        }
        // 连接成分后面接的是别的说法——模型确实报了一个状态，而它不是工具给的那个
        return !rest.isBlank();
    }

    private static boolean isMoney(String value) {
        return MONEY.matcher(value).find();
    }

    /**
     * 金额比对换算成分再比。<b>不能用字符串比</b>：工具声明的是 {@code ¥128.00}，
     * 回答里写 {@code 128 元} 或 {@code ¥128} 都是同一个数，逐字比会把它们判成矛盾。
     */
    private static Long toCents(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = MONEY.matcher(text);
        String digits = null;
        if (matcher.find()) {
            digits = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        } else if (text.trim().matches("\\d+(?:\\.\\d{1,2})?")) {
            digits = text.trim();
        }
        if (digits == null) {
            return null;
        }
        try {
            return Math.round(Double.parseDouble(digits) * 100);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
