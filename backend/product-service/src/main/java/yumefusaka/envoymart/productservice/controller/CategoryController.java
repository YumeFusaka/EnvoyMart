package yumefusaka.envoymart.productservice.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.productservice.model.BrandView;
import yumefusaka.envoymart.productservice.model.CategoryNode;
import yumefusaka.envoymart.productservice.service.CategoryService;

import java.util.List;

/**
 * 类目与品牌：导航用的基础数据。公开读，不需要登录（见网关的 PUBLIC_RULES）。
 */
@RestController
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping("/categories/tree")
    public Result<List<CategoryNode>> tree() {
        return Result.success(categoryService.tree());
    }

    @GetMapping("/brands")
    public Result<List<BrandView>> brands() {
        return Result.success(categoryService.brands());
    }
}
