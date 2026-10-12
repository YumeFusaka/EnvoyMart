package yumefusaka.envoymart.productservice.projection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.productservice.search.ProductSyncService;
import yumefusaka.envoymart.productservice.service.impl.ProductCacheService;

/** 一个任务一次独立本地事务；失败保任务、未知来源不改AT后像。 */
@Slf4j
@Service
public class ProductProjectionTransaction {
    public enum Outcome { COMPLETE, NOT_PRESENT, EXTERNAL_FAILED }
    private final ProductProjectionStore store;
    private final ProductProjectionInputs inputs;
    private final ProductProjectionSources sources;
    private final ProductCacheService cache;
    private final ObjectProvider<ProductSyncService> index;
    private final ProjectionExternalCalls external;
    public ProductProjectionTransaction(ProductProjectionStore store, ProductProjectionInputs inputs, ProductProjectionSources sources,
                                        ProductCacheService cache, ObjectProvider<ProductSyncService> index, ProjectionExternalCalls external) {
        this.store = store; this.inputs = inputs; this.sources = sources; this.cache = cache; this.index = index; this.external = external;
    }

    @io.seata.spring.annotation.GlobalLock
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 20)
    public Outcome process(long id) {
        long started = System.nanoTime();
        var task = store.lockTask(id);
        if (task == null) return Outcome.NOT_PRESENT;
        var guard = store.lockGuard(task.spuId());
        inputs.lock(task.spuId());
        sources.requireStable(task.spuId());
        long taskVersion = Math.max(store.maximumTaskId(task.spuId()), guard.lastAppliedTaskId());
        var publisher = index.getIfAvailable();
        if (publisher != null && guard.versionBase() == null) throw new IllegalStateException("ES版本基数尚未持久初始化");
        String mode = publisher == null ? "CACHE_ONLY" : "CACHE_AND_ES";
        try (var scope = new ProjectionPublicationScope(task.spuId())) {
            var projection = publisher == null ? null : publisher.prepareCurrent(task.spuId(), Math.addExact(guard.versionBase(), taskVersion));
            String publishStage = "CACHE";
            try {
                external.run(() -> cache.invalidateProjection(task.spuId(), taskVersion));
                publishStage = "ES";
                if (publisher != null) external.run(() -> publisher.publishPrepared(projection));
            } catch (RuntimeException failed) {
                Throwable cause = failed;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                String errorType = publishStage + ":" + cause.getClass().getSimpleName();
                store.failed(task.id(), errorType);
                log.warn("[商品投影] 阶段=外部失败保留待办 taskId={} spuId={} xid={} mode={} errorType={} durationMs={}", id, task.spuId(), task.globalXid(), mode, errorType, (System.nanoTime()-started)/1_000_000);
                return Outcome.EXTERNAL_FAILED;
            }
            store.complete(task.id(), task.spuId(), taskVersion, mode);
            log.info("[商品投影] 阶段=外部发布已接受等待本地提交 taskId={} spuId={} xid={} mode={} version={} durationMs={}", id, task.spuId(), task.globalXid(), mode, taskVersion, (System.nanoTime()-started)/1_000_000);
            return Outcome.COMPLETE;
        }
    }
}
