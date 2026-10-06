package yumefusaka.envoymart.agent.core.task;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨轮指代消解 —— 把「第二个」「第一件」「它」这类没说清对象的说法，
 * 补成上一轮推荐里的具体条目。
 * <p>
 * <b>为什么要在代码里做，而不是交给模型。</b>模型看得到历史，也大多能猜对；
 * 但它猜的是一次**概率**，而这个决定后面接的是不可逆的写操作（加购、下单）。
 * 「把第二个加进购物车」猜错一条，用户拿到的是一笔他没要的订单。
 * 序号是确定的事实——上一轮推荐里第 2 条就是第 2 条，没有任何歧义——
 * 所以它该由代码解析，而不是由模型回忆。
 * <p>
 * <b>只认「明显是序号指代」的说法。</b>「那个」「它」不在这里处理：
 * 它们可能指向一件商品，也可能指向一个订单、一个成分，靠字面判不出来，
 * 交给模型更合适。而「第二个 / 第一件 / 第 3 款」这类句式的指向是唯一的。
 * <p>
 * <b>取不到时原样返回。</b>没有上一轮推荐、序号越界、说法本身不完整——
 * 任何一种都退回原文，让模型照常处理。补错对象比不补坏得多。
 */
public final class ReferenceResolver {

    /**
     * 序号指代：第 <中文或阿拉伯数字> <量词>。
     * <p>
     * 量词表是有限的：商品语境里只会有「个 / 件 / 款 / 种 / 条 / 瓶 / 盒」。
     * 不把它们收全，代价是那一种说法退回给模型猜；收得比这更宽，
     * 代价是把「第 3 天」「第一周」这类时间说法也当成商品序号。
     */
    private static final Pattern ORDINAL = Pattern.compile(
            "第\\s*([一二三四五六七八九十\\d]+)\\s*(个|件|款|种|条|瓶|盒|种商品|个商品)");

    private static final List<String> CHINESE_DIGITS =
            List.of("零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十");

    private ReferenceResolver() {
    }

    /**
     * 把消息里的序号指代补成明确的商品名。
     *
     * @param message   用户本轮原话
     * @param lastItems 上一轮（或本会话最近一次）给出的候选，顺序就是「第一 / 第二」的所指
     * @return 补全后的句子；无需补全或补不了时原样返回 {@code message}
     */
    public static String resolve(String message, List<String> lastItems) {
        if (message == null || message.isBlank() || lastItems == null || lastItems.isEmpty()) {
            return message;
        }
        Matcher matcher = ORDINAL.matcher(message);
        if (!matcher.find()) {
            return message;
        }
        Integer index = ordinalOf(matcher.group(1));
        if (index == null || index < 1 || index > lastItems.size()) {
            // 越界同样原样返回：用户说「第五个」而只有三条时，正确行为是让模型
            // 去澄清「只有三款」，而不是我们替他挑一个最接近的
            return message;
        }
        String item = lastItems.get(index - 1);
        if (item == null || item.isBlank()) {
            return message;
        }
        // 只替换第一处：一句话里出现两个序号（「把第一个和第三个加进去」）时，
        // 逐个替换会让后半句的指向也一起变——那不是这条规则能承担的事
        //
        // **补进去的必须是干净的商品名。**正文里的候选条目带着括号注释
        // （「植物蛋白粉（肌力方，豌豆+糙米双蛋白）」），那串注释是给人看的补充说明，
        // 而它会跟着改写句一路走进检索：实测模型把整句「把植物蛋白粉（肌力方，豌豆+糙米双蛋白）
        // 加进购物车」当成 query 传给 product_search，搜出 0 条，然后引用 {$0.skuId}
        // 解析不到值——最后报给用户的是「skuId 必须是数字」，与真实成因隔了三层。
        String replaced = message.substring(0, matcher.start())
                + "第" + matcher.group(1) + matcher.group(2) + "（" + coreName(item) + "）"
                + message.substring(matcher.end());
        return replaced;
    }

    /**
     * 从候选条目里取出**核心商品名**，去掉分类与品牌括号。
     * <p>
     * 「植物蛋白粉（肌力方，豌豆+糙米双蛋白）」→「植物蛋白粉」。
     * 括号里是规格、品牌或成分说明，它们对「用户指的是哪一条」没有帮助
     * （同一个名字在列表里只出现一次），但对检索有害——检索是拿它去匹配商品名的。
     * <p>
     * 嵌套括号一并处理（「（本草纪（进口））」），用计数而不是第一个 {@code （}
     * 就直接截断：截在第一个左括号上在多层括号时会留下半截。
     */
    static String coreName(String item) {
        if (item == null) {
            return null;
        }
        int cut = item.length();
        int depth = 0;
        for (int i = 0; i < item.length(); i++) {
            char ch = item.charAt(i);
            if (ch == '（' || ch == '(') {
                if (depth == 0) {
                    cut = i;
                    break;
                }
                depth++;
            }
        }
        return item.substring(0, cut).strip();
    }

    /**
     * 从上一轮的回答正文里认出候选商品名，按出现顺序。
     * <p>
     * <b>为什么从正文里认，而不是从卡片里拿。</b>卡片（{@code recommendedProducts}）
     * 在 D3 之前恒为空，而用户看到的候选一直是正文里那几个加粗的名字——
     * 用户说「第二个」时，数的就是他屏幕上看到的那几条。以正文为准，
     * 才和用户的所指一致。
     * <p>
     * <b>认的是「有序号的加粗条目」，不是「任何加粗文字」。</b>这一条踩过坑：
     * 澄清追问里的选项也是加粗的（{@code **A. 运动增肌 / 日常补充蛋白质**}），
     * 只认加粗会把两个选项当成两款商品，于是「把第二个加进购物车」被补成
     * 「第二个（A. 运动增肌…）」，模型拿整句去搜商品 → 搜出 0 条 →
     * {@code $0.skuId} 解析不到 → 用户看到「skuId 必须是数字」。
     * <p>
     * 判据落在<b>条目自身的序号</b>上：商品候选的固定写法是「1. 名字」「2. 名字」，
     * 而澄清选项是「A.」「B.」或没有序号。要求阿拉伯序号是最小且足够的那条约束——
     * 它不依赖任何一次 prompt 的措辞，也就不会随提示词改两句话就失效。
     */
    private static final Pattern BOLD_ITEM = Pattern.compile("\\*\\*([^*\\n]{2,40})\\*\\*");

    /** 条目开头的阿拉伯序号：「1.」「2、」「3．」——商品候选列表的固定写法 */
    private static final Pattern LEADING_ORDINAL = Pattern.compile("^(\\d{1,2})\\s*[.、．)）]\\s*");

    public static List<String> candidatesOf(String reply) {
        if (reply == null || reply.isBlank()) {
            return List.of();
        }
        List<String> items = new java.util.ArrayList<>();
        // 按行扫，而不是只扫加粗片段：序号往往写在加粗**外面**
        // （「1. **日常健身 / 增肌**」），只扫片段会看不见它，
        // 于是澄清选项又一次被当成商品候选 —— 这正是本方法第一次改错的地方。
        for (String line : reply.split("\\R")) {
            String trimmed = line.strip();
            // 允许行首的列表符号（「- 」「•」），序号才是真正的判据。
            //
            // **星号不能当列表符号剥掉。**`**加粗**` 是模型最常用的强调写法，
            // 而 `^\*\s*` 会把它的第一个星号吃掉，剩下的 `*1. 乳清蛋白粉**` 不再是
            // 一对完整的加粗标记 —— 于是这条候选被静默跳过，`candidatesOf` 返回空，
            // 「第二个」补不上所指。这个 bug 的表现是「什么都没发生」，最难查。
            // 要剥星号列表符号，只在它后面**不是**星号时才剥（`* item` vs `**bold**`）。
            String body = trimmed.replaceFirst("^-(?!-)\\s*", "").replaceFirst("^•\\s*", "")
                    .replaceFirst("^\\*(?!\\*)\\s*", "").strip();
            // 名字只从这一行的加粗片段里取：正文里对商品的解释性括注不该混进名字
            Matcher bold = BOLD_ITEM.matcher(body);
            if (!bold.find()) {
                continue;
            }
            // **序号可以在加粗里，也可以在加粗外**，两种写法模型都用过：
            //   「**1. 乳清蛋白粉**」（推荐列表）
            //   「1. **日常健身 / 增肌**」（澄清选项）
            // 只在加粗里找（或只在加粗外找）都会漏掉一半形态，而漏掉的代价不是
            // 「少认一条候选」，是把澄清选项误当成商品、给用户补一个莫名其妙的所指。
            // 所以判据是「这一行有没有阿拉伯序号」——它在哪一侧不影响它是序号。
            String boldText = bold.group(1).strip();
            boolean ordinalOutside = LEADING_ORDINAL.matcher(body).find()
                    && !LEADING_ORDINAL.matcher(boldText).find()
                    && bold.start() <= 4;
            if (!LEADING_ORDINAL.matcher(boldText).find() && !ordinalOutside) {
                // 整行没有阿拉伯序号 → 强调句或叙述，不是候选条目
                continue;
            }
            // 加粗里含序号时先把它剥掉；序号在外侧时加粗本身就是名字
            String name = LEADING_ORDINAL.matcher(boldText).replaceFirst("");
            name = name.split("——|—|－|:：|：" )[0].strip();
            name = coreName(name);
            if (!name.isBlank() && name.length() <= 40 && !items.contains(name)) {
                items.add(name);
            }
        }
        return items;
    }

    /** 中文数字或阿拉伯数字 → int。认不出返回 null（让调用方原样返回，而不是当成 0） */
    private static Integer ordinalOf(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String t = token.strip();
        if (t.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(t);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (t.length() == 1) {
            int index = CHINESE_DIGITS.indexOf(t);
            return index < 0 ? null : index;
        }
        // 只处理「十X」与「X十」两种两位数形态——候选列表不会超过这个量级，
        // 再多就只能靠模型澄清了（而那时它本来也问不清是哪个）
        if (t.startsWith("十")) {
            int ones = CHINESE_DIGITS.indexOf(t.substring(1));
            return ones < 0 ? null : 10 + ones;
        }
        if (t.endsWith("十")) {
            int tens = CHINESE_DIGITS.indexOf(t.substring(0, 1));
            return tens <= 0 ? null : tens * 10;
        }
        return null;
    }
}
