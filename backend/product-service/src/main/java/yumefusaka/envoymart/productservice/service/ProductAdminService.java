package yumefusaka.envoymart.productservice.service;

import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.productservice.model.admin.AdminSpuDetail;
import yumefusaka.envoymart.productservice.model.admin.AdminSpuQuery;
import yumefusaka.envoymart.productservice.model.admin.AdminSpuSummary;
import yumefusaka.envoymart.productservice.model.admin.SpuUpsertRequest;
import yumefusaka.envoymart.productservice.model.admin.StockAdjustRequest;

/**
 * 商品域的管理侧写读。
 * <p>
 * 与 {@link ProductService} 的分工是<b>可见性</b>，不是权限：那个接口面向买家，
 * 只看上架商品、只返回启用中的 SKU；这个接口面向运营，能看到全部状态。
 * 权限由 {@code @RequireAdmin} 在控制器上判定，这一层不重复做——把授权写进业务方法，
 * 会让「这个方法到底该谁能调」变成散在代码里的隐含前提。
 * <p>
 * <b>所有写方法都会连带做两件事</b>：失效商品缓存、同步 ES 索引。它们是同一份数据的
 * 两个派生副本，漏掉任何一个的症状都是「改了但界面没变」——而那种现象会被当成缓存没生效，
 * 排查方向从一开始就是错的。
 */
public interface ProductAdminService {

    PageResult<AdminSpuSummary> list(AdminSpuQuery query);

    /** 编辑表单的回显数据。商品不存在时抛 {@code IllegalArgumentException} */
    AdminSpuDetail detail(Long spuId);

    /**
     * 新建商品，返回新 id。
     *
     * @param operatorId 操作人，写进库存流水的 biz_id —— 没有它，流水只能证明「库存变了」，
     *                   证明不了「谁改的」
     */
    Long create(SpuUpsertRequest request, String operatorId);

    void update(Long spuId, SpuUpsertRequest request, String operatorId);

    /** 上下架。{@code status} 只接受 0 与 1 */
    void changeStatus(Long spuId, Integer status);

    /** 单独调整某个 SKU 的库存：管理台商品列表上的快捷操作 */
    void adjustStock(Long skuId, StockAdjustRequest request, String operatorId);

    /**
     * 删除商品（连同 SKU、规格、参数）。
     * <p>
     * <b>卖过的商品不允许删</b>：历史订单引用的是 SKU id，删掉之后那些订单会变成
     * 查不到商品的空壳。这类商品要走下架。
     */
    void delete(Long spuId);
}
