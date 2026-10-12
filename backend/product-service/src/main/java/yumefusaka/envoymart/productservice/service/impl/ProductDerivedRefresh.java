package yumefusaka.envoymart.productservice.service.impl;

import org.springframework.stereotype.Component;
import yumefusaka.envoymart.productservice.projection.ProductProjectionRequests;

import java.util.Collection;
import java.util.List;

/** 商品业务只登记持久任务；缓存与索引统一由稳定源消费者刷新。 */
@Component
public class ProductDerivedRefresh {
    private final ProductProjectionRequests requests;
    public ProductDerivedRefresh(ProductProjectionRequests requests) { this.requests = requests; }
    public void request(Long spuId) {
        if (spuId != null) requests.request(List.of(spuId));
    }
    public void request(Collection<Long> spuIds) {
        if (spuIds != null && !spuIds.isEmpty()) requests.request(spuIds);
    }
}
