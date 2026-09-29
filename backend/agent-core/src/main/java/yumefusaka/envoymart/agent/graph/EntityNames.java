package yumefusaka.envoymart.agent.graph;

/**
 * 实体名的规范化，以及「这个字符算不算空白」的判定。
 * <p>
 * <b>为什么必须只有一份实现</b>：规范化后的名字就是图谱的<b>节点键</b>。
 * ai-service 用它给商品建键、knowledge-service 用它拿用户输入去查图，
 * 两边只要有一处不一致——哪怕只差对不换行空格 {@code U+00A0} 的处理——
 * 同一个实体就会分裂成两个节点，多跳路径在中间断开，
 * 而图上看起来只是「有两组相似的节点」，排查起来毫无线索。
 * 这两份实现原先各写了一遍，是本类存在的直接理由。
 * <p>
 * 只做空白与大小写，<b>不做同义词归一</b>：把「维生素D3」并到「维生素D」
 * 是医学上错误的一步（D3 是 D 的一种形式，不是同义词），
 * 这种判断属于人工维护的别名表，不该由这里猜。
 */
public final class EntityNames {

    private EntityNames() {
    }

    /**
     * 算不算「空白」。
     * <p>
     * {@link Character#isWhitespace} <b>不覆盖不换行空格</b>：实测 {@code U+00A0}、
     * {@code U+2007}、{@code U+202F} 全部返回 false，{@link String#strip()} 也不剥离它们。
     * 而模型输出与从网页复制来的文本里这三者都很常见，于是「维生素 D3」存进去是
     * {@code 维生素 d3}、查的时候是 {@code 维生素d3}：<b>查不到，且不报错</b>。
     * {@link Character#isSpaceChar} 正好覆盖这一类。
     * <p>
     * 零宽字符两个判定都不算，单独列出来是因为它们在模型输出里同样出现过：
     * 零宽空格会被当成一个「真实存在的字符」，让两个看起来一样的名字成为两个节点。
     */
    private static final char[] ZERO_WIDTH = {0x200B, 0xFEFF, 0x2060};

    public static boolean isBlank(char c) {
        if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
            return true;
        }
        for (char z : ZERO_WIDTH) {
            if (c == z) {
                return true;
            }
        }
        return false;
    }

    /**
     * 去掉空白、转小写 —— 图谱节点键的规范化。
     *
     * @return 空字符串表示这个名字不可用（null、纯空白都会落到这里）
     */
    public static String normalize(String name) {
        return filter(name, true);
    }

    /**
     * 只去空白，<b>保留大小写</b>。
     * <p>
     * 引文比对用这个而不是 {@link #normalize}：大小写是原文的一部分，
     * 归一之后再回原文里定位会整体对不上。
     */
    public static String stripBlanks(String s) {
        return filter(s, false);
    }

    private static String filter(String s, boolean lower) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isBlank(c)) {
                sb.append(lower ? Character.toLowerCase(c) : c);
            }
        }
        return sb.toString();
    }
}
