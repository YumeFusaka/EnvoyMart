package yumefusaka.envoymart.productservice.service;

import yumefusaka.envoymart.productservice.model.BrandView;
import yumefusaka.envoymart.productservice.model.CategoryNode;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface CategoryService {

    /** 完整类目树。一次查出扁平列表后在内存里组装，避免逐层查库 */
    List<CategoryNode> tree();

    List<BrandView> brands();

    /**
     * 某个类目及其全部后代类目的 id。
     * <p>
     * 用户在二级类目下要看到挂在三级类目的商品，所以按类目筛选时必须展开子树。
     */
    List<Long> selfAndDescendantIds(Long categoryId);

    /** 批量取类目名。列表页要显示类目，逐个查会退化成 N+1 */
    Map<Long, String> categoryNames(Collection<Long> ids);

    /**
     * 批量取「类目及其全部祖先」，由近及远。
     * <p>
     * 给 SKU 快照用：优惠券的类目作用域要判「这行商品落不落在这棵子树里」，
     * 而类目树长什么样只有这边知道。随快照下发之后，核销侧不必回查商品服务
     * —— 那条路在订单事务里，不该为一次类目换算多挂一个下游依赖。
     * <p>
     * 走类目的物化路径（{@code category.path}，形如 {@code 1/5}，含自身）一次拆出，
     * 不递归查库。
     */
    Map<Long, List<Long>> categoryPaths(Collection<Long> ids);

    Map<Long, String> brandNames(Collection<Long> ids);
}
