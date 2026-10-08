package yumefusaka.envoymart.aiservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import yumefusaka.envoymart.contract.ProductDetail;
import yumefusaka.envoymart.contract.ProductSummary;
import yumefusaka.envoymart.contract.SkuSnapshot;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;

import java.util.List;

@FeignClient(name = "product-service", url = "${services.product-service-url:http://127.0.0.1:9002}")
public interface ProductClient {

    @GetMapping("/products/skus")
    Result<List<SkuSnapshot>> skus(@RequestParam("ids") List<Long> ids);

    /**
     * 商品详情 —— SPU 摘要 + <b>全部 SKU（含规格编号）</b>。
     * <p>
     * <b>为什么检索接口不够用。</b>{@code /products/search} 返回的是 SPU 级摘要：
     * 有价格区间，却没有任何规格编号。而加购认的是 SKU——同一个 SPU 下不同规格
     * 是不同的价格、不同的库存，选错规格等于下错单。少了这一步，
     * 「帮我加进购物车」就只剩一个商品名可用，模型只能猜一个编号（实测它猜的是 0）。
     * <p>
     * 路径与检索下游一致（{@code GET /products/{id}}），返回体是更完整的详情；
     * 调用方按需取 SKU 列表。
     */
    @GetMapping("/products/{id}")
    Result<ProductDetail> getProduct(@PathVariable("id") Long id);


    /**
     * 商品检索 —— <b>推荐链路唯一的一条路</b>。
     * <p>
     * 走 ES 那条检索（与搜索页同一个接口），而不是另开一个「推荐专用」的窄接口：
     * 原来那个 {@code /products/recommendations} 只有 {@code query} + {@code limit} 两个参数，
     * 装不下价格区间、否定条件、品类这些约束，而它的下游是 MySQL 的整串
     * {@code like '%关键词%'}——同一句「找商品」写在两条路上，一条走 ES 分词、
     * 一条走子串匹配，**同一个词两条路给出不同结果只是时间问题**。
     */
    @GetMapping("/products/search")
    Result<PageResult<ProductSummary>> search(@RequestParam("keyword") String keyword,
                                              @RequestParam(value = "minPrice", required = false) Long minPrice,
                                              @RequestParam(value = "maxPrice", required = false) Long maxPrice,
                                              @RequestParam(value = "excludeKeywords", required = false) String excludeKeywords,
                                              @RequestParam(value = "attributes", required = false) List<String> attributes,
                                              @RequestParam(value = "size", defaultValue = "3") int size);

    /**
     * 全量在售商品目录 —— 知识图谱做实体链接用的那份对照表。
     * <p>
     * 走 {@code /products/internal/}：网关对这个前缀一律 404，只有服务间直连够得着。
     * 数据本身不敏感，但它是「不分页拉全库」的通道，不该对匿名请求开放。
     */
    @GetMapping("/products/internal/catalog")
    Result<List<ProductSummary>> catalog();
}
