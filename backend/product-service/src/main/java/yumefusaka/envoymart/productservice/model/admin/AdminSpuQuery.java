package yumefusaka.envoymart.productservice.model.admin;

import lombok.Data;

/**
 * 管理端的商品列表查询条件。
 * <p>
 * <b>为什么不复用 {@code ProductQuery} 加一个 status 字段</b>：那个对象是公开接口的入参，
 * 而它<b>刻意不含 status</b>——公开列表永远只看上架商品，这是硬规则。
 * 在它上面开一个 status 字段，等于给「匿名用户传 ?status=0 就能看到全部下架商品」
 * 留了一条只靠调用方自觉的路。管理端要的是「按状态筛选」，那就单独有一个对象，
 * 让两条路径的默认行为各自写死在各自的类型上。
 */
@Data
public class AdminSpuQuery {

    /** 名称 / 副标题 / 商品编码，任一命中即可 */
    private String keyword;
    /** 传一级或二级类目时自动展开整棵子树，与公开列表一致 */
    private Long categoryId;
    private Long brandId;
    /** 0 下架 / 1 上架 / null 全部 */
    private Integer status;
    /**
     * sales / updated（默认）。
     * <p>
     * 白名单比公开列表小：管理列表的诉求是「最近改过的排前面」和「卖得好的排前面」，
     * 价格排序在这里没有意义（运营不会按价格翻商品）。
     */
    private String sort;

    private Integer page = 0;
    private Integer size = 20;

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

    /** 上限 100，与公开列表同一条线：不设上限的话 `size=100000` 一个请求就能把整库捞出来 */
    public int safeSize() {
        if (size == null || size <= 0) {
            return 20;
        }
        return Math.min(size, 100);
    }
}
