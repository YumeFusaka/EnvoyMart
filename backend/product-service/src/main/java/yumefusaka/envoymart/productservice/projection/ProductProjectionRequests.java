package yumefusaka.envoymart.productservice.projection;

import lombok.extern.slf4j.Slf4j;
import org.apache.seata.core.context.RootContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

/** 与商品本地事务一起登记；不创建临时线程，不直接发布任何派生数据。 */
@Slf4j
@Service
public class ProductProjectionRequests {
    private final ProductProjectionStore store;
    private final org.springframework.beans.factory.ObjectProvider<ProductProjectionWorker> workers;
    public ProductProjectionRequests(ProductProjectionStore store, org.springframework.beans.factory.ObjectProvider<ProductProjectionWorker> workers) { this.store = store; this.workers = workers; }

    @io.seata.spring.annotation.GlobalLock
    @Transactional
    public void request(Collection<Long> spuIds) {
        register(spuIds, true);
    }

    /** 启动重建只登记，ES故障不能按目标数量串行拖住服务就绪。 */
    @io.seata.spring.annotation.GlobalLock
    @Transactional
    public void rebuild(Collection<Long> spuIds) {
        register(spuIds, false);
    }

    private void register(Collection<Long> spuIds, boolean wakeImmediately) {
        String xid = RootContext.getXID();
        if (spuIds == null) return;
        var tasks = new java.util.ArrayList<ProductProjectionStore.Task>();
        spuIds.stream().filter(java.util.Objects::nonNull).filter(id -> id > 0).distinct().sorted().forEach(spuId -> {
            tasks.add(new ProductProjectionStore.Task(store.append(spuId, xid), spuId, xid));
            log.info("[商品投影] 阶段=已登记 spuId={} xid={} 来源={}", spuId, xid, xid == null ? "本地事务" : "SeataAT");
        });
        if (wakeImmediately && xid == null && !tasks.isEmpty()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() {
                    for (var task : tasks) {
                        try { if (!workers.getObject().consume(task)) break; }
                        catch (RuntimeException failed) {
                            log.warn("[商品投影] 阶段=即时唤醒失败保留待办 taskId={} errorType={}", task.id(), failed.getClass().getSimpleName());
                            break;
                        }
                    }
                }
            });
        }
    }
}
