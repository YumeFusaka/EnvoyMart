package yumefusaka.envoymart.agent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计数词与列表条数的对账 —— 发现 P（N1 文档 §2.13）。
 *
 * <p><b>它补的是剔除带来的第二层损伤。</b>剔除一句话，用户少看到一行；但如果正文里
 * 还写着「这三档」，用户看到的是「三档」配两条——这已经不是少一条，而是一句
 * <b>自相矛盾的陈述</b>。实测形态（{@code u1003-1791680700402}）：
 *
 * <pre>
 *   - SKU1（400IU×90 粒，瓶装）59.00 元
 *   - SKU2（400IU×180 粒，瓶装）99.00 元
 * 这三档都在你 200 元以内的预算里。…（SKU1 / SKU2 / SKU3）
 * </pre>
 *
 * <p><b>为什么不做通用语义比对。</b>「这句话里的数对不对」是语义判断，用规则去做
 * 会退化成又一次「拿词表判语义」。这里只认一种<b>可数</b>的形态：同一段落内既有
 * 一个列表块、又有紧跟在它后面的计数词——此时「列表有几行」是文本本身就能数出来的
 * 事实，不需要理解任何内容。判据窄，但命中的每一处都是真的错。
 *
 * <p><b>「计数词指向这个列表」也必须有判据，不能默认。</b>反例：
 * <pre>
 *   - 商品A
 *   - 商品B
 *   三种支付方式都可以。
 * </pre>
 * 这里的「三种」数的是支付方式，与列表行数无关；把它改成「两种」会把一句正确的话
 * 改错，而且改完读起来同样通顺、看不出发生过什么。所以还要求计数词后面紧跟着
 * 一个<b>谓词性成分</b>（都 / 均 / 全 / 是 / 在 / 正 / 可）——「这三档<b>都</b>在你预算里」
 * 是主谓结构，计数词是主语；「三种支付方式都可以」里计数词只是定语，紧跟着的是名词。
 *
 * <p><b>改计数词，不补条目。</b>补条目要凭空造内容（被剔除的那条正是因为无依据），
 * 而计数词是模型自己写的修饰，改它不引入任何新事实。
 *
 * <p><b>列表块多于一个时不改。</b>两个块与计数词的配对关系不确定，那时宁可不动——
 * 猜错配对会把一处错误换成另一处错误，而且看不出来。
 */
public final class CountReconciliation {

    /** 列表项：可选缩进 + 列表标记 + 内容。与 {@code CitationVerifier} 的列表判据同形 */
    private static final Pattern LIST_ITEM = Pattern.compile("^[ \\t]*(?:[-*+]|\\d+[.)])\\s+\\S.*$");

    /**
     * 计数词：可选的指示词 + 数词 + 量词。
     * <p>
     * 量词表只收「用于列举一组同类事物」的那几个：{@code 档/款/种/个/条/项/类}。
     * 刻意不收「次/年/元」这类——它们数的是时间与金额，与列表行数没有关系，
     * 收进来只会制造误判。
     */
    private static final Pattern COUNT_WORD = Pattern.compile(
            "(这|那)?([一二两三四五六七八九十\\d]{1,3})(\\s*)(档|款|种|个|条|项|类)");

    /**
     * 计数词后面紧跟着的谓词性成分 —— 见类注释里那个反例。
     * <p>
     * 它是「这个计数词是主语」的判据：{@code 都/均/全}是范围副词，{@code 是/在/正/可}
     * 直接起谓语。反过来说，后面紧跟名词的计数词是定语，指代对象在名词里，不在列表里。
     */
    private static final Pattern PREDICATE_AFTER = Pattern.compile("^[ \\t]*(都|均|全|是|在|正|可)");

    /** 段落分隔：空行（容忍 CRLF 与行内空白） */
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\r?\\n[ \\t]*\\r?\\n");

    /** 中文数字 → 数值。「两」与「二」都是 2，不能靠字符表下标换算 */
    private static final String CN_NUMERALS = "零一二三四五六七八九";

    private static final String CN_TEN = "十";

    /**
     * 对账整篇回复。
     * <p>
     * 逐段落处理而不是全文扫：段落是「一组列表 + 它的小结句」的自然边界，
     * 跨段落配对会让「上一段的列表」被下一段的计数词认领。
     */
    public static String reconcile(String reply) {
        if (reply == null || reply.isBlank()) {
            return reply;
        }
        StringBuilder out = new StringBuilder(reply.length());
        Matcher separator = PARAGRAPH_BREAK.matcher(reply);
        int start = 0;
        while (separator.find()) {
            out.append(reconcileParagraph(reply.substring(start, separator.start())));
            out.append(separator.group());
            start = separator.end();
        }
        out.append(reconcileParagraph(reply.substring(start)));
        return out.toString();
    }

    private static String reconcileParagraph(String paragraph) {
        if (paragraph == null || paragraph.isEmpty()) {
            return paragraph;
        }
        String[] lines = paragraph.split("\n", -1);
        List<Integer> listLines = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (LIST_ITEM.matcher(lines[i].stripTrailing()).matches()) {
                listLines.add(i);
            }
        }
        if (listLines.isEmpty()) {
            return paragraph;
        }
        // 只认一个连续列表块：多个块时配对关系不确定，见类注释
        int first = listLines.get(0);
        int last = listLines.get(listLines.size() - 1);
        if (last - first + 1 != listLines.size()) {
            return paragraph;
        }
        int itemCount = listLines.size();
        // 只认列表块结束之后出现的计数词：块之前的数（「以下三档」）指代对象可能是别处
        int afterList = 0;
        for (int i = 0; i <= last; i++) {
            afterList += lines[i].length() + 1;
        }
        Matcher matcher = COUNT_WORD.matcher(paragraph);
        while (matcher.find()) {
            if (matcher.start() < afterList) {
                continue;
            }
            if (!PREDICATE_AFTER.matcher(paragraph.substring(matcher.end())).find()) {
                // 定语而非主语：指代对象不是这个列表，不动
                continue;
            }
            int written = countValue(matcher.group(2));
            if (written <= 0 || written == itemCount) {
                // 数对得上（或读不出数）：本段没有要对账的东西
                return paragraph;
            }
            // 原分隔照抄（group 3）：数字与量词之间的空格是模型的排版，改了会动到
            // 与这件事无关的地方，而正文的其余部分我们一律不碰
            String replacement = (matcher.group(1) == null ? "" : matcher.group(1))
                    + renderCount(itemCount, matcher.group(2)) + matcher.group(3) + matcher.group(4);
            return paragraph.substring(0, matcher.start()) + replacement + paragraph.substring(matcher.end());
        }
        return paragraph;
    }

    /** 读出计数词里的数；读不出返回 -1（调用方据此跳过，而不是当成 0） */
    private static int countValue(String raw) {
        if (raw == null || raw.isEmpty()) {
            return -1;
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        if (CN_TEN.equals(raw)) {
            return 10;
        }
        if (raw.startsWith(CN_TEN)) {
            // 「十五」这类：十位省略
            int unit = digitValue(raw.charAt(1));
            return unit < 0 || raw.length() != 2 ? -1 : 10 + unit;
        }
        if (raw.endsWith(CN_TEN)) {
            int tens = digitValue(raw.charAt(0));
            return tens <= 0 || raw.length() != 2 ? -1 : tens * 10;
        }
        if (raw.length() == 1) {
            return digitValue(raw.charAt(0));
        }
        return -1;
    }

    private static int digitValue(char ch) {
        if (ch == '两') {
            return 2;
        }
        int index = CN_NUMERALS.indexOf(ch);
        return index < 0 ? -1 : index;
    }

    /** 按原计数词的写法渲染新数：阿拉伯数字就还写阿拉伯数字，中文就还写中文 */
    private static String renderCount(int count, String original) {
        if (original.chars().allMatch(Character::isDigit)) {
            return String.valueOf(count);
        }
        if (count == 2) {
            // 「两款/两档」比「二款/二档」自然，且与常见写法一致
            return "两";
        }
        if (count >= 0 && count < CN_NUMERALS.length()) {
            return String.valueOf(CN_NUMERALS.charAt(count));
        }
        return String.valueOf(count);
    }

    private CountReconciliation() {
    }
}