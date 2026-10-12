package yumefusaka.envoymart.productservice.projection;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 结构与实际AT代理检查完成后才能发布，worker与缓存miss共用同一启动状态。 */
@Component
public class ProductProjectionReadiness {
    private volatile boolean ready;
    @EventListener(ApplicationReadyEvent.class)
    public void ready() { ready = true; }
    public boolean isReady() { return ready; }
    public void requireReady() {
        if (!ready) throw new ProjectionSourceUnresolvedException("商品服务正在准备，请稍后重试");
    }
}
