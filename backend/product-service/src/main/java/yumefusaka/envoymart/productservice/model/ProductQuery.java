package yumefusaka.envoymart.productservice.model;

import lombok.Data;

/** 商品列表查询条件。价格区间单位是「分」，与库里保持一致 */
@Data
public class ProductQuery {

    private String keyword;
    /** 传一级或二级类目时，自动展开整棵子树 */
    private Long categoryId;
    private Long brandId;
    private Long minPrice;
    private Long maxPrice;
    /** sales / price_asc / price_desc / newest，见实现里的白名单。其它值一律回落到 sales */
    private String sort;

    private Integer page = 0;
    private Integer size = 20;

    /**
     * 对外页码，<b>从 0 开始</b>——数据库那条路与 ES 的 {@code PageRequest.of} 都按这个基准，
     * {@code PageResult.page} 与前端「第 N 页」也是。
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
            return 20;
        }
        return Math.min(size, 100);
    }
}
