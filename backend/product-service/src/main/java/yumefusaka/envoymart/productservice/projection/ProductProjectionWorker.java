package yumefusaka.envoymart.productservice.projection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 候选扫描与锁读事务分开；游标避免未知GT长期挡住后面的任务。 */
@Slf4j
@Component
public class ProductProjectionWorker {
    private final ProductProjectionStore store;
    private final ProductProjectionMetadata metadata;
    private final ProductProjectionTransaction transactions;
    private long cursor;
    private final ProductProjectionReadiness readiness;
    public ProductProjectionWorker(ProductProjectionStore store, ProductProjectionMetadata metadata, ProductProjectionTransaction transactions, ProductProjectionReadiness readiness) {
        this.store = store; this.metadata = metadata; this.transactions = transactions; this.readiness = readiness;
    }

    @Scheduled(fixedDelay = 500, initialDelay = 500)
    public void poll() {
        if (!readiness.isReady()) return;
        var candidates = store.candidates(cursor, 16);
        if (candidates.isEmpty()) { cursor = 0; return; }
        for (var candidate : candidates) {
            cursor = candidate.id();
            consume(candidate);
        }
    }

    /** 即时唤醒与定时恢复共用相同屏障，失败不能把已提交的业务响应变为失败。 */
    public boolean consume(ProductProjectionStore.Task task) {
        if (!readiness.isReady()) return false;
        var stage = new java.util.concurrent.atomic.AtomicReference<>("版本元数据");
        long started = System.nanoTime();
        try {
            var result = SeataProjectionContext.withoutXid(() -> {
                metadata.prepare(task.spuId());
                stage.set("稳定来源消费事务");
                return transactions.process(task.id());
            });
            if (result == ProductProjectionTransaction.Outcome.COMPLETE) {
                log.info("[商品投影] 阶段=已完成 taskId={} spuId={} xid={} 本地提交=已返回 durationMs={}", task.id(), task.spuId(), task.globalXid(), (System.nanoTime()-started)/1_000_000);
            }
            return result != ProductProjectionTransaction.Outcome.EXTERNAL_FAILED;
        } catch (RuntimeException waiting) {
            log.info("[商品投影] 阶段=等待重试 failureStage={} taskId={} spuId={} xid={} errorType={} durationMs={}", stage.get(), task.id(), task.spuId(), task.globalXid(), waiting.getClass().getSimpleName(), (System.nanoTime()-started)/1_000_000);
            return false;
        }
    }
}
