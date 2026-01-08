package yumefusaka.envoymart.orderservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.orderservice.model.ProductSnapshot;
import yumefusaka.envoymart.orderservice.model.StockDeductRequest;

@FeignClient(name = "product-service", url = "${services.product-service-url:http://127.0.0.1:9002}")
public interface ProductClient {

    @GetMapping("/products/{id}")
    Result<ProductSnapshot> getProduct(@PathVariable("id") Long id);

    @PostMapping("/products/stock/deduct")
    Result<Void> deductStock(@RequestBody StockDeductRequest request);
}
