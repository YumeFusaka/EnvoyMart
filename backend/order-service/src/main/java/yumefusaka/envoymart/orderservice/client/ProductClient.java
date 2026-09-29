package yumefusaka.envoymart.orderservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.SkuSnapshot;
import yumefusaka.envoymart.contract.StockChangeRequest;

import java.util.List;

/**
 * 商品服务客户端。
 * <p>
 * 只保留 SKU 粒度的接口：购物车与订单要的都是「具体规格的价格与库存」，
 * 商品粒度的详情接口在业务里根本没有使用场景。
 */
@FeignClient(name = "product-service", url = "${services.product-service-url:http://127.0.0.1:9002}")
public interface ProductClient {

    /**
     * 按 SKU id 批量取快照。
     * <p>
     * 参数名必须显式写：Feign 生成请求时不依赖编译期的 {@code -parameters}，
     * 省略 name 会直接报错。
     */
    @GetMapping("/products/skus")
    Result<List<SkuSnapshot>> getSkus(@RequestParam("ids") List<Long> ids);

    @PostMapping("/products/stock/deduct")
    Result<Void> deductStock(@RequestBody StockChangeRequest request);

    @PostMapping("/products/stock/restore")
    Result<Void> restoreStock(@RequestBody StockChangeRequest request);
}
