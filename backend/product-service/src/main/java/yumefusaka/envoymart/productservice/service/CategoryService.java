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

    Map<Long, String> brandNames(Collection<Long> ids);
}
