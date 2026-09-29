package yumefusaka.envoymart.aiservice.tool;

/**
 * 金额展示 —— <b>分 → 元，只做展示</b>。
 * <p>
 * 工具输出是喂给模型的文本，模型会原样复述给用户。这里出错的代价不是「显示不好看」，
 * 而是<b>模型把错数字当事实说出去</b>：
 * <ul>
 *   <li>字段串了（拿 {@code price} 去读 {@code minPrice}）→ 输出 {@code null 元}，
 *       模型会说「该商品价格为 null 元」；</li>
 *   <li>单位串了（把「分」当「元」）→ 4900 分的商品被说成 4900 元，差 100 倍。</li>
 * </ul>
 * 两种情况都不会抛异常、不会报错、不会进日志错误分支，只能靠这一层显式转换挡住。
 * <p>
 * 系统内部一律用「分」（BIGINT），<b>只有面向人的文本才除以 100</b>。
 */
public final class Money {

    private Money() {
    }

    /** 分 → "12.34 元"。null 返回「暂无报价」而不是 "null 元" */
    public static String yuan(Long cents) {
        return cents == null ? "暂无报价" : "%d.%02d 元".formatted(cents / 100, Math.abs(cents % 100));
    }

    /**
     * 价格区间。上下限相等时只回一个价格——
     * 「199.00-199.00 元」会让用户以为有两个不同价位。
     */
    public static String yuanRange(Long minCents, Long maxCents) {
        if (minCents == null && maxCents == null) {
            return "暂无报价";
        }
        if (minCents == null || maxCents == null || minCents.equals(maxCents)) {
            return yuan(minCents == null ? maxCents : minCents);
        }
        return "%s-%s".formatted(yuan(minCents), yuan(maxCents));
    }
}
