package yumefusaka.envoymart.productservice.projection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

/** 全量也只是任务来源，不能在构造期绕过稳定源屏障。 */
@Slf4j
@Component
public class ProductProjectionStartup {
    private final ProductProjectionStore store;
    private final ProductProjectionRequests requests;
    public ProductProjectionStartup(ProductProjectionStore store, ProductProjectionRequests requests) { this.store = store; this.requests = requests; }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        var targets = store.startupTargets();
        SeataProjectionContext.withoutXid(() -> { requests.rebuild(targets); return null; });
        log.info("[商品投影] 阶段=启动全量已登记 targets={} 消费链=稳定来源屏障", targets.size());
    }
}
