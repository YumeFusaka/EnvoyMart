package yumefusaka.envoymart.productservice.projection;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.productservice.search.ProductSyncService;

/** ES版本基数先独立持久提交，外部超时后不得重新分配较小版本。 */
@Service
public class ProductProjectionMetadata {
    private final ProductProjectionStore store;
    private final ObjectProvider<ProductSyncService> index;
    private final ProjectionExternalCalls external;
    public ProductProjectionMetadata(ProductProjectionStore store, ObjectProvider<ProductSyncService> index, ProjectionExternalCalls external) {
        this.store = store; this.index = index; this.external = external;
    }

    @io.seata.spring.annotation.GlobalLock
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 8)
    public void prepare(long spuId) {
        store.ensureGuard(spuId);
        var guard = store.lockGuard(spuId);
        var publisher = index.getIfAvailable();
        if (publisher != null && guard.versionBase() == null) {
            store.initializeVersionBase(spuId, Math.addExact(external.call(() -> publisher.currentVersion(spuId)), 1));
        }
    }
}
