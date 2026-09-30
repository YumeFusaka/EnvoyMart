package yumefusaka.envoymart.productservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import yumefusaka.envoymart.productservice.search.ProductSyncService;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 商品变更后刷新它的两份派生副本：缓存与 ES 索引。
 * <p>
 * <b>为什么这件事必须收在一处</b>：商品的任何一次写入——改字段、上下架、调库存、
 * 改类目名——都要刷新这两份副本，漏掉任何一个的症状都是「改了但界面没变」，
 * 而那种现象会被当成缓存没生效，排查方向从一开始就是错的。散在各处手写，
 * 迟早有人只写一半；而且「注册在事务提交之后」这条约束更容易在抄写时丢掉。
 * <p>
 * <b>为什么在提交之后</b>：在事务里执行的话，一旦回滚，ES 里已经是「改过之后」的商品
 * 而库里没有——搜索会展示一个不存在的商品，且这个不一致不会报错。
 * 缓存那一侧还有第二个理由：先删缓存再提交会留出一个窗口，期间任何一次读都会把旧值
 * 重新灌进缓存，此后 24 小时都是错的；删在提交之后，窗口后面没有能重新写入旧值的读。
 */
@Slf4j
@Component
public class ProductDerivedRefresh {

    private final ProductCacheService cacheService;
    /** 索引同步可以被开关关掉，那时这个 bean 不存在 */
    private final ObjectProvider<ProductSyncService> syncServiceProvider;

    public ProductDerivedRefresh(ProductCacheService cacheService,
                                 ObjectProvider<ProductSyncService> syncServiceProvider) {
        this.cacheService = cacheService;
        this.syncServiceProvider = syncServiceProvider;
    }

    public void afterCommit(Long spuId) {
        if (spuId != null) {
            afterCommit(List.of(spuId));
        }
    }

    /**
     * 提交后刷新这一批商品。
     * <p>
     * 去重在这里做而不是靠调用方：改类目名会一次传进一批在同一个类目下的商品，
     * 其中重复的 id 会让同一个 key 被删两次、同一条索引写两次——不致命，但纯是白做的。
     */
    public void afterCommit(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return;
        }
        Collection<Long> distinct = new LinkedHashSet<>(spuIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refresh(distinct);
                }
            });
        } else {
            // 不在事务里（如库存扣减自己的事务边界之外）就立即执行
            refresh(distinct);
        }
    }

    /**
     * 逐条刷新。
     * <p>
     * <b>代价与受影响商品数成正比</b>，改一次类目名就是这一类目下所有商品各删一次缓存、
     * 各写一次索引。目录规模是几十到几百条时这是最直接的做法；真涨到成千上万条，
     * 该换的是这里——改成发一条消息由后台批量重建——而不是给循环加一个「最多处理 N 条」的上限：
     * 上限会让超出部分<b>永久停在旧值</b>，且看不出来。
     */
    private void refresh(Collection<Long> spuIds) {
        for (Long spuId : spuIds) {
            // 删缓存自己吞异常（读可以降级），不需要我们兜
            cacheService.evictProductCache(spuId);

            ProductSyncService syncService = syncServiceProvider.getIfAvailable();
            if (syncService == null) {
                continue;
            }
            try {
                // 商品已被删除时 syncOne 会把索引里的那条也清掉
                syncService.syncOne(spuId);
            } catch (Exception e) {
                // 索引是派生数据：它没跟上不该让已经成功的保存变成失败。
                // 下一次该商品的任何变更都会把索引覆盖回正确值
                log.warn("[管理] ES 索引同步失败，商品本身已保存: spuId={}", spuId, e);
            }
        }
    }
}
