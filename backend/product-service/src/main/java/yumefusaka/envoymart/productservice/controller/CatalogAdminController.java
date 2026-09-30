package yumefusaka.envoymart.productservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.RequireAdmin;
import yumefusaka.envoymart.productservice.model.CategoryNode;
import yumefusaka.envoymart.productservice.model.admin.AdminBrand;
import yumefusaka.envoymart.productservice.model.admin.BrandUpsertRequest;
import yumefusaka.envoymart.productservice.model.admin.CategoryUpsertRequest;
import yumefusaka.envoymart.productservice.service.CatalogAdminService;

import java.util.List;

/**
 * 类目与品牌的管理接口。
 * <p>
 * 类目不写类级 {@code @RequestMapping}，两个资源各用完整路径（{@code /categories/admin/**}
 * 与 {@code /brands/admin/**}）——公开的 {@code CategoryController} 也是这么组织的，
 * 因为它们本来就挂在两个不同的顶层前缀下，硬塞进一个类级前缀反而要写全路径。
 * 权限判定标在类上，两个资源一起生效，与 {@code KnowledgeAdminController} 一致。
 */
@RequireAdmin
@RestController
public class CatalogAdminController {

    private final CatalogAdminService catalogAdminService;

    public CatalogAdminController(CatalogAdminService catalogAdminService) {
        this.catalogAdminService = catalogAdminService;
    }

    // ==================== 类目 ====================

    /**
     * 完整类目树，<b>含停用</b>。
     * <p>
     * 不复用公开的 {@code GET /categories/tree}：那个只回启用中的，管理台拿它做编辑
     * 会出现「刚停用的类目一刷新就不见了，再也点不回去」。
     */
    @GetMapping("/categories/admin/tree")
    public Result<List<CategoryNode>> tree() {
        return Result.success(catalogAdminService.tree());
    }

    @PostMapping("/categories/admin")
    public Result<Long> createCategory(@Valid @RequestBody CategoryUpsertRequest request) {
        return Result.success(catalogAdminService.createCategory(request));
    }

    /** 改 {@code parentId} 即为移动，会连带重算整棵子树的层级与路径 */
    @PutMapping("/categories/admin/{id}")
    public Result<Void> updateCategory(@PathVariable("id") Long id,
                                       @Valid @RequestBody CategoryUpsertRequest request) {
        catalogAdminService.updateCategory(id, request);
        return Result.success();
    }

    /** 有子类目、挂着商品、或被参数模板引用时拒绝删除，提示改为停用 */
    @DeleteMapping("/categories/admin/{id}")
    public Result<Void> deleteCategory(@PathVariable("id") Long id) {
        catalogAdminService.deleteCategory(id);
        return Result.success();
    }

    // ==================== 品牌 ====================

    @GetMapping("/brands/admin")
    public Result<List<AdminBrand>> brands() {
        return Result.success(catalogAdminService.brands());
    }

    @PostMapping("/brands/admin")
    public Result<Long> createBrand(@Valid @RequestBody BrandUpsertRequest request) {
        return Result.success(catalogAdminService.createBrand(request));
    }

    @PutMapping("/brands/admin/{id}")
    public Result<Void> updateBrand(@PathVariable("id") Long id,
                                    @Valid @RequestBody BrandUpsertRequest request) {
        catalogAdminService.updateBrand(id, request);
        return Result.success();
    }

    /** 还有商品挂着这个品牌时拒绝删除 */
    @DeleteMapping("/brands/admin/{id}")
    public Result<Void> deleteBrand(@PathVariable("id") Long id) {
        catalogAdminService.deleteBrand(id);
        return Result.success();
    }
}
