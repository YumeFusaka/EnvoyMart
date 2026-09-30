package yumefusaka.envoymart.productservice.service;

import yumefusaka.envoymart.productservice.model.CategoryNode;
import yumefusaka.envoymart.productservice.model.admin.AdminAttribute;
import yumefusaka.envoymart.productservice.model.admin.AdminBrand;
import yumefusaka.envoymart.productservice.model.admin.AttributeUpsertRequest;
import yumefusaka.envoymart.productservice.model.admin.BrandUpsertRequest;
import yumefusaka.envoymart.productservice.model.admin.CategoryUpsertRequest;

import java.util.List;

/**
 * 类目与品牌的管理侧写读。
 * <p>
 * 与 {@link CategoryService} 的分工是<b>可见性</b>：那个只回启用中的，供买家导航；
 * 这个回全部状态，供运营维护。
 *
 * <h3>三条贯穿全类的语义</h3>
 * <ul>
 *   <li><b>停用类目只是从导航树里消失</b>，不影响已挂在它下面的商品。商品有自己的
 *       上下架开关——让一个类目级的勾选连带下掉一批商品，是那种「点一下、看不出发生了什么」
 *       的操作。要停卖商品就用商品自己的开关。</li>
 *   <li><b>删不掉是常态</b>：被商品引用、有子类目、被参数模板引用的类目，以及被商品引用的品牌，
 *       一律拒绝删除并说明原因，让运营改停用。删掉一个还在被引用的类目，商品会挂在一个
 *       不存在的 id 上——它在管理列表里看起来完全正常，只有前台按类目点进去才发现是空的。</li>
 *   <li><b>改名要连带刷新商品</b>：类目名与品牌名冗余在商品详情与搜索索引里，
 *       改完不刷的话，前台和搜索结果里显示的还是旧名字。</li>
 * </ul>
 */
public interface CatalogAdminService {

    /** 完整类目树，<b>含停用</b>。停用的类目在管理台必须看得见，否则改不回来 */
    List<CategoryNode> tree();

    Long createCategory(CategoryUpsertRequest request);

    /**
     * 编辑类目。{@code parentId} 变化即为「移动」，会连带重算整棵子树的 level 与 path。
     *
     * @throws IllegalArgumentException 移到自己的后代下、超出三级、同级重名
     */
    void updateCategory(Long categoryId, CategoryUpsertRequest request);

    /** @throws IllegalStateException 有子类目、挂着商品、或被参数模板引用 */
    void deleteCategory(Long categoryId);

    // ==================== 参数模板 ====================

    /**
     * 某个类目的参数模板，按 {@code sort} 排序。
     * <p>
     * 参数模板此前<b>只在库里、没有出口</b>：商品编辑页要摆哪几个参数输入框无从得知，
     * 而 {@code SpuUpsertRequest.attributes} 又要求 {@code attributeId} 必须已存在 ——
     * 结果是这个字段事实上只能由 SQL 直接写库来填。补上读出口，它才是一条走得通的链路。
     */
    List<AdminAttribute> attributes(Long categoryId);

    /** @throws IllegalArgumentException 类目不存在、同类目下重名 */
    Long createAttribute(Long categoryId, AttributeUpsertRequest request);

    /** @throws IllegalArgumentException 参数项不存在、同类目下重名 */
    void updateAttribute(Long attributeId, AttributeUpsertRequest request);

    /** @throws IllegalStateException 已有商品填过这个参数 */
    void deleteAttribute(Long attributeId);

    /** 全部品牌，含停用 */
    List<AdminBrand> brands();

    Long createBrand(BrandUpsertRequest request);

    void updateBrand(Long brandId, BrandUpsertRequest request);

    /** @throws IllegalStateException 还有商品挂在这个品牌上 */
    void deleteBrand(Long brandId);
}
