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
import yumefusaka.envoymart.productservice.model.SuggestItem;
import yumefusaka.envoymart.contract.ProductSummary;
import yumefusaka.envoymart.contract.SkuSnapshot;
import yumefusaka.envoymart.contract.StockChangeRequest;
import yumefusaka.envoymart.productservice.search.HotKeywordService;
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
    private final HotKeywordService hotKeywordService;

    public ProductController(ProductService productService,
                             ProductSearchService searchService,
                             StockService stockService,
                             HotKeywordService hotKeywordService) {
        this.productService = productService;
        this.searchService = searchService;
        this.stockService = stockService;
        this.hotKeywordService = hotKeywordService;
    }

    /**
     * 按条件浏览。默认走 MySQL 的库内查询。
     * <p>
     * 与 {@code /products/search} 的分工：这条适合「点类目、翻页」这类结构化浏览，
     * 条件都能落到索引上；那条走 ES 全文索引，适合带关键词的搜索。
     * <p>
     * <b>但库内查询认不全这套条件</b>：属性筛选与否定条件只有 ES 那条路实现得了。
     * 所以带这两者时在这里改道，而不是让它们<b>静默失效</b>——
     * 静默失效的样子是「筛了等于没筛」：结果多出一批明确被排除的商品，
     * 接口不报错、日志里也没有异常，只有对着需求一条条数才发现不对。
     * 分流判据在 {@link ProductQuery#needsFullTextIndex()}，两个入口共用同一个问题。
     * <p>
     * 参数直接用 {@link ProductQuery} 对象绑定，而不是一长串 {@code @RequestParam}：
     * 后者要依赖编译期的 {@code -parameters} 才能从字节码里读出参数名，
     * 而那个标志在增量编译下**会时灵时不灵** —— 表现为「昨天还好好的接口今天报
     * 参数名缺失」。对象绑定走的是 setter，与编译标志无关。
     */
    @GetMapping
    public Result<PageResult<ProductSummary>> list(ProductQuery query) {
        return Result.success(query.needsFullTextIndex()
                ? searchService.search(query)
                : productService.list(query));
    }

    /** 全文检索。索引是 SPU 粒度，返回的商品价格是一个区间 */
    @GetMapping("/search")
    public Result<PageResult<ProductSummary>> search(ProductQuery query) {
        return Result.success(searchService.search(query));
    }

    /**
     * 搜索联想。输入两个字就要出候选，所以走 ES 的前缀查询（见 {@link ProductSearchService#suggest}）。
     * <p>
     * 这也是这条链路上唯一会被「每敲一个字」调用的接口，网关给它单配了一个限流桶：
     * 与商品浏览共用配额时，几个人同时打字就能把翻页请求挤掉。
     */
    @GetMapping("/suggest")
    public Result<List<SuggestItem>> suggest(@RequestParam("q") String q,
                                             @RequestParam(value = "limit", defaultValue = "8") int limit) {
        return Result.success(searchService.suggest(q, limit));
    }

    /** 热门搜索词。来自真实搜索行为的排行（Redis ZSET），冷启动回落到种子词 */
    @GetMapping("/hot-keywords")
    public Result<List<String>> hotKeywords(@RequestParam(value = "limit", defaultValue = "8") int limit) {
        return Result.success(hotKeywordService.top(limit));
    }

    /**
     * 批量取商品摘要，供订单、评价等「已经拿到一批 spuId，只需要补商品信息」的场景。
     * <p>
     * <b>与 {@code /{id}} 的差别</b>：详情接口一次一个 id，二十条订单就要二十次调用；
     * 这个接口一次拿完。返回顺序与入参无关，查不到的 id 直接缺席——调用方按 id 建映射，
     * 不要按下标对——商品被删掉时缺一个比返回一个 null 更容易被发现。
     */
    @GetMapping("/summaries")
    public Result<List<ProductSummary>> summaries(@RequestParam("ids") List<Long> ids) {
        return Result.success(productService.summaries(ids));
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

    /**
     * 内部接口：全量商品目录，供 ai-service 建知识图谱时做实体链接。
     * <p>
     * 走 {@code /internal/} 而不是复用公开的 {@code /products}：后者分页且每页上限 100，
     * 图谱要的是「一次拿到全部」——分页拉取会把一次完整的目录变成若干次可以中途失败的调用，
     * 而漏掉的那一页商品在图上的表现是「这几个商品查不到任何关系」。
     * <p>
     * <b>网关必须为这个前缀配反向排除</b>（见 {@code JwtGatewayFilter} 的
     * {@code INTERNAL_ONLY_PREFIXES}）：{@code /products/**} 是公开规则，
     * 只加接口不改网关的话，这个「内部」接口会连匿名请求一起放行。
     */
    @GetMapping("/internal/catalog")
    public Result<List<ProductSummary>> catalog() {
        return Result.success(productService.catalog());
    }

    /**
     * 内部接口：扣减 / 回补库存。网关对 {@code /products/stock/} 前缀一律 404。
     * <p>
     * <b>这个方法的防护只有「网关够不着」这一条</b>：它不读身份、不校验调用方，
     * 服务间调用也确实是 Feign 直连 9002，因此没有任何一层会检查「谁在扣库存」。
     * 后果是把 9002 直接暴露出去就等于把库存开关交给对方；本地开发环境端口是裸的，
     * 靠的是「不进生产」而不是某个机制。要真正守住，得给服务间调用加凭证
     * （{@code InternalCallFilter} 只在请求声称了身份时才校验，这个方法不声称身份）。
     * <p>
     * 因此<b>网关那份排除清单是这个接口唯一的一道门</b>，而它曾经漏登记过
     * （{@code /orders/internal/}）。新增内部接口忘了登记现在会让构建失败：
     * 见 {@code InternalEndpointCoverageTest}。
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
