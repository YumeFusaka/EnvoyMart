package yumefusaka.envoymart.aiservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import yumefusaka.envoymart.contract.ProductSummary;
import yumefusaka.envoymart.common.result.Result;

import java.util.List;

@FeignClient(name = "product-service", url = "${services.product-service-url:http://127.0.0.1:9002}")
public interface ProductClient {

    @GetMapping("/products/{id}")
    Result<ProductSummary> getProduct(@PathVariable("id") Long id);

    @GetMapping("/products/recommendations")
    Result<List<ProductSummary>> recommend(@RequestParam("query") String query,
                                            @RequestParam("limit") int limit);

    /**
     * 全量在售商品目录 —— 知识图谱做实体链接用的那份对照表。
     * <p>
     * 走 {@code /products/internal/}：网关对这个前缀一律 404，只有服务间直连够得着。
     * 数据本身不敏感，但它是「不分页拉全库」的通道，不该对匿名请求开放。
     */
    @GetMapping("/products/internal/catalog")
    Result<List<ProductSummary>> catalog();
}
