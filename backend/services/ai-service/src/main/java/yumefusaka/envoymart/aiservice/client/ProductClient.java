package yumefusaka.envoymart.aiservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import yumefusaka.envoymart.aiservice.model.ProductResponse;
import yumefusaka.envoymart.common.result.Result;

import java.util.List;

@FeignClient(name = "product-service", url = "${services.product-service-url:http://127.0.0.1:9002}")
public interface ProductClient {

    @GetMapping("/products/{id}")
    Result<ProductResponse> getProduct(@PathVariable("id") Long id);

    @GetMapping("/products/recommendations")
    Result<List<ProductResponse>> recommend(@RequestParam("query") String query,
                                            @RequestParam("limit") int limit);
}
