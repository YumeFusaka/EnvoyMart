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

    public int safePage() {
        return page == null || page < 0 ? 0 : page;
    }

    /** 上限 100：不设上限的话，一个 `size=100000` 的请求就能把整库捞出来 */
    public int safeSize() {
        if (size == null || size <= 0) {
            return 20;
        }
        return Math.min(size, 100);
    }
}
