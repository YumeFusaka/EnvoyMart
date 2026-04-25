package yumefusaka.envoymart.productservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.productservice.model.ProductResponse;
import yumefusaka.envoymart.productservice.model.StockDeductRequest;
import yumefusaka.envoymart.productservice.service.ProductService;

import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;
    private final yumefusaka.envoymart.productservice.search.ProductSearchService searchService;

    public ProductController(ProductService productService,
                             yumefusaka.envoymart.productservice.search.ProductSearchService searchService) {
        this.productService = productService;
        this.searchService = searchService;
    }

    @GetMapping
    public Result<List<ProductResponse>> list(@RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String category) {
        return Result.success(productService.listProducts(keyword, category));
    }

    /**
     * ES 搜索引擎入口：多字段组合检索，支持分页
     */
    @GetMapping("/search")
    public Result<List<ProductResponse>> search(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String category,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return Result.success(searchService.search(keyword, category, page, size));
    }

    @GetMapping("/{id}")
    public Result<ProductResponse> detail(@PathVariable Long id) {
        return Result.success(productService.getProduct(id));
    }

    @GetMapping("/recommendations")
    public Result<List<ProductResponse>> recommend(@RequestParam String query,
                                                   @RequestParam(defaultValue = "3") int limit) {
        return Result.success(productService.recommendProducts(query, limit));
    }

    @PostMapping("/stock/deduct")
    public Result<Void> deduct(@Valid @RequestBody StockDeductRequest request) {
        productService.deductStock(request);
        return Result.success();
    }
}
