package yumefusaka.envoymart.productservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.ProductDetail;
import yumefusaka.envoymart.productservice.model.ProductQuery;
import yumefusaka.envoymart.contract.ProductSummary;
import yumefusaka.envoymart.contract.SkuSnapshot;
import yumefusaka.envoymart.contract.StockChangeRequest;
import yumefusaka.envoymart.productservice.search.ProductSearchService;
import yumefusaka.envoymart.productservice.service.ProductService;
import yumefusaka.envoymart.productservice.service.StockService;

import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;
    private final ProductSearchService searchService;
    private final StockService stockService;

    public ProductController(ProductService productService,
                             ProductSearchService searchService,
                             StockService stockService) {
        this.productService = productService;
        this.searchService = searchService;
        this.stockService = stockService;
    }

    /**
     * 按条件浏览。走 MySQL 的库内查询。
     * <p>
     * 与 {@code /products/search} 的分工：这条适合「点类目、翻页」这类结构化浏览，
     * 条件都能落到索引上；那条走 ES 全文索引，适合带关键词的搜索。
     * <p>
     * 参数直接用 {@link ProductQuery} 对象绑定，而不是一长串 {@code @RequestParam}：
     * 后者要依赖编译期的 {@code -parameters} 才能从字节码里读出参数名，
     * 而那个标志在增量编译下**会时灵时不灵** —— 表现为「昨天还好好的接口今天报
     * 参数名缺失」。对象绑定走的是 setter，与编译标志无关。
     */
    @GetMapping
    public Result<PageResult<ProductSummary>> list(ProductQuery query) {
        return Result.success(productService.list(query));
    }

    /** 全文检索。索引是 SPU 粒度，返回的商品价格是一个区间 */
    @GetMapping("/search")
    public Result<PageResult<ProductSummary>> search(ProductQuery query) {
        return Result.success(searchService.search(query));
    }

    @GetMapping("/{id}")
    public Result<ProductDetail> detail(@PathVariable("id") Long id) {
        return Result.success(productService.detail(id));
    }

    /**
     * 按 SKU id 批量取快照，供购物车与订单组装。
     * <p>
     * 与 {@code /{id}} 不冲突：Spring 里字面量路径优先于模板路径，
     * 不会把它当成「id 为 skus」的详情请求。
     */
    @GetMapping("/skus")
    public Result<List<SkuSnapshot>> skus(@RequestParam("ids") List<Long> ids) {
        return Result.success(productService.skus(ids));
    }

    @GetMapping("/recommendations")
    public Result<List<ProductSummary>> recommend(@RequestParam("query") String query,
                                                  @RequestParam(value = "limit", defaultValue = "3") int limit) {
        return Result.success(productService.recommend(query, limit));
    }

    /**
     * 内部接口：扣减 / 回补库存。网关对 {@code /products/stock/} 前缀一律 404，
     * 只有服务间通过 Feign 直连才够得着。
     * <p>
     * 维度是 SKU 而不是商品 —— 价格与库存都挂在 SKU 上，用商品维度扣减会出现
     * 「扣了 90 粒装，180 粒装的库存也少了」。
     */
    @PostMapping("/stock/deduct")
    public Result<Void> deduct(@Valid @RequestBody StockChangeRequest request) {
        stockService.deduct(request);
        return Result.success();
    }

    @PostMapping("/stock/restore")
    public Result<Void> restore(@Valid @RequestBody StockChangeRequest request) {
        stockService.restore(request);
        return Result.success();
    }

}
