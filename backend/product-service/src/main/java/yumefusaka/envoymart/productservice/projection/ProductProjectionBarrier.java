package yumefusaka.envoymart.productservice.projection;

import org.springframework.stereotype.Component;

import java.util.function.LongFunction;

/** 在进入Spring事务代理前隔离XID，保留调用者上下文。 */
@Component
public class ProductProjectionBarrier {
    private final ProductProjectionSnapshotTransaction snapshots;
    public ProductProjectionBarrier(ProductProjectionSnapshotTransaction snapshots) { this.snapshots = snapshots; }
    public <T> T read(long spuId, LongFunction<T> publish) {
        return SeataProjectionContext.withoutXid(() -> snapshots.read(spuId, publish));
    }
}
