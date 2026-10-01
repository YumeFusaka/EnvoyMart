package yumefusaka.envoymart.orderservice.model;

import lombok.Data;

/**
 * 「我的订单」的查询条件。
 * <p>
 * 只有页签与分页三项 —— 管理端那套多条件筛选（{@code AdminOrderQuery}）不适用于这里：
 * 用户面对的是自己的几十单，客服面对的才是全库。
 */
@Data
public class OrderListQuery {

    /** 见 {@link OrderTab}。为空回落到「全部」，取值非法返回 400 */
    private String tab;

    private Integer page = 0;
    private Integer size = 10;

    public OrderTab tabOrDefault() {
        return OrderTab.parse(tab);
    }

    /**
     * 对外页码，<b>从 0 开始</b>——与 {@code PageResult.page}、前端「第 N 页」一致。
     * <p>
     * <b>不要拿它直接构造 MyBatis-Plus 的 {@code Page}</b>：那边 {@code current} 从 1 开始，
     * 且 {@code offset()} 对 {@code current <= 1} 一律返回 0，于是第 0 页与第 1 页查出
     * 同一批数据、之后整体后移一页，最后一页永远取不到。要传给 MP 用 {@link #mpCurrent()}。
     */
    public int zeroBasedPage() {
        return page == null || page < 0 ? 0 : page;
    }

    /** MyBatis-Plus 的页码从 1 开始。这步转换只留这一个出处，免得各调用点各自 +1 */
    public long mpCurrent() {
        return zeroBasedPage() + 1L;
    }

    /** 上限 100：不设上限的话，一个 `size=100000` 的请求就能把整库捞出来 */
    public int safeSize() {
        if (size == null || size <= 0) {
            return 10;
        }
        return Math.min(size, 100);
    }
}
