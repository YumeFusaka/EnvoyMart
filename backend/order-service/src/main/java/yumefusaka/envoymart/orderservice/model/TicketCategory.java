package yumefusaka.envoymart.orderservice.model;

/**
 * 工单分类。
 * <p>
 * 是给客服的分诊依据，不是给用户的填空题——所以只有四档、含义不重叠。
 * 分得太细（"物流慢"与"物流异常"分开）的结果是用户随手选一个，
 * 分类失去统计意义。
 */
public enum TicketCategory {

    /** 订单问题：不发货、少发漏发、订单状态异常 */
    ORDER,
    /** 退款售后：退款进度、售后被拒 */
    REFUND,
    /** 商品咨询：成分、适用人群、保质期 */
    PRODUCT,
    /** 其他 */
    OTHER;

    public static TicketCategory parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            // null 会以 NPE 的形式穿过 IllegalArgumentException 的捕获，落进兜底变成 500。
            // 分类来自请求体，给错值是客户端的错，应当是 400
            throw new IllegalArgumentException("未知的工单分类：" + value);
        }
    }

    public String text() {
        return switch (this) {
            case ORDER -> "订单问题";
            case REFUND -> "退款售后";
            case PRODUCT -> "商品咨询";
            case OTHER -> "其他";
        };
    }
}
