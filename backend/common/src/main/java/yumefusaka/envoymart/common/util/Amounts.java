package yumefusaka.envoymart.common.util;

/**
 * 金额展示 —— 内部单位是「分」，给用户看的一律是「元」。
 * <p>
 * 库里的金额统一存分（整数运算不会出现浮点误差），但<b>这个单位属于实现细节</b>。
 * 直接把它拼进给用户的文案会得到「还差 7400 元」「最多可退 32000 元」这类说法 ——
 * 金额看着没错，只是大了一百倍、还换了个单位，用户读到的是另一个数。
 * <p>
 * 收成一个方法而不是各处手写 {@code / 100.0}：换算散落在各服务里，
 * 迟早会漏掉一处，而漏掉的那处不会有编译错误，只会静默地说错话。
 */
public final class Amounts {

    private Amounts() {
    }

    /**
     * 分值转成给用户看的元文本。
     * <p>
     * 整元不带小数位（「还差 74 元」比「还差 74.00 元」更像人话），
     * 非整元保留两位。
     */
    public static String yuan(long fen) {
        if (fen % 100 == 0) {
            return String.valueOf(fen / 100);
        }
        return String.format("%.2f", fen / 100.0);
    }
}
