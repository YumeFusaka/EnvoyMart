package yumefusaka.envoymart.orderservice.model;

/**
 * 售后类型与状态的取值。
 * <p>
 * 与订单状态同样的理由放在枚举里：散落的字符串字面量改一处要全仓库搜，
 * 漏掉任何一处都不会有编译期提示。
 */
public final class AfterSaleType {

    private AfterSaleType() {
    }

    public static final String REFUND_ONLY = "REFUND_ONLY";
    public static final String RETURN_REFUND = "RETURN_REFUND";
    public static final String EXCHANGE = "EXCHANGE";

    public static String text(String type) {
        return switch (type) {
            case REFUND_ONLY -> "仅退款";
            case RETURN_REFUND -> "退货退款";
            case EXCHANGE -> "换货";
            default -> type;
        };
    }

    /** 需要把货寄回来的类型。仅退款不需要物流环节 */
    public static boolean requiresReturn(String type) {
        return RETURN_REFUND.equals(type) || EXCHANGE.equals(type);
    }

    public static boolean isValid(String type) {
        return REFUND_ONLY.equals(type) || RETURN_REFUND.equals(type) || EXCHANGE.equals(type);
    }
}
