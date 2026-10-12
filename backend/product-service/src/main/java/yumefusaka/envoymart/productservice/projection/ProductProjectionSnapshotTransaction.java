package yumefusaka.envoymart.productservice.projection;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.LongFunction;

/** 缓存miss在源锁与版本边界内完成回源和回填，未知ID不制造永久元数据。 */
@Service
public class ProductProjectionSnapshotTransaction {
    private final ProductProjectionStore store;
    private final ProductProjectionInputs inputs;
    private final ProductProjectionSources sources;
    private final ProductProjectionReadiness readiness;
    public ProductProjectionSnapshotTransaction(ProductProjectionStore store, ProductProjectionInputs inputs, ProductProjectionSources sources, ProductProjectionReadiness readiness) {
        this.store = store; this.inputs = inputs; this.sources = sources; this.readiness = readiness;
    }

    @io.seata.spring.annotation.GlobalLock
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public <T> T read(long spuId, LongFunction<T> publish) {
        readiness.requireReady();
        ProductProjectionStore.Guard guard = null;
        if (store.knownTarget(spuId)) {
            store.ensureGuard(spuId);
            guard = store.lockGuard(spuId);
        }
        inputs.lock(spuId);
        sources.requireStable(spuId);
        long version = Math.max(store.maximumTaskId(spuId), guard == null ? 0 : guard.lastAppliedTaskId());
        return publish.apply(version);
    }
}
