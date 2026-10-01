package yumefusaka.envoymart.orderservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 下单路径上的库存锁（Redisson）。
 * <p>
 * <b>这里曾经还兼着购物车缓存，那个缓存已经删掉了</b>，理由是它缓存的是一份**派生结果**：
 * {@code CartItemResponse} 里的价格、库存、上下架状态全部来自 product-service，
 * 而失效触发只有「用户改了自己的购物车」这一个。两个输入，一个触发条件 ——
 * 表现是管理员下架或改价之后，购物车能对着 72 小时的旧快照说「有货、就这个价」，
 * 到结算页才被服务端算出的另一个数打脸。
 * <p>
 * 正确的做法不是把 TTL 调小，而是<b>缓存只能放在数据所有者那一侧</b>：product-service
 * 改了商品自己能立刻失效（它已经有 {@code derivedRefresh} 这条链路），order-service
 * 改了商品却收不到通知。所以商品侧的两级缓存留着，购物车这份删掉 ——
 * 代价是读一次购物车多一次批量取快照的调用，换来的是购物车上的数字与结算页永远一致。
 */
@Slf4j
@Service
public class StockLockService {

    private static final String STOCK_LOCK_PREFIX = "stock:lock:";
    private static final long LOCK_LEASE_SECONDS = 10;

    /**
     * 抢锁等待上限（秒）。
     * <p>
     * <b>为什么做成可配置</b>：它是一个纯粹的余量参数，取决于"临界区持有时长 × 并发数"。
     * 临界区里加了观测（javaagent）或事务协调（Seata）之后，持有时长会变，这个值就得跟着调——
     * 写死在代码里意味着每次都要重编译才能试一个数。
     * <p>
     * <b>调大的代价</b>：它把"快速拒绝"换成了"慢速排队"。等待期内线程一直被占着，
     * 真实高并发下会让上游线程池先耗尽。所以正确的方向是缩短临界区，而不是一直加大这个值——
     * 它只是个安全余量，不是容量。
     */
    @Value("${envoymart.stock.lock-wait-seconds:3}")
    private long lockWaitSeconds;

    private final RedissonClient redissonClient;

    public StockLockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** 锁粒度是 <b>SKU</b> 而不是 SPU：可售与库存都记在 SKU 上，同一个 SPU 的两个规格本来就不该互相排队 */
    public RLock getStockLock(Long skuId) {
        return redissonClient.getLock(STOCK_LOCK_PREFIX + skuId);
    }

    /**
     * 尝试加分布式锁：抢到返回 true，等待超时返回 false。
     * <p>
     * <b>这里没有降级选项</b>：读可以退回数据库，而"下单要不要拿锁"不能——
     * 放行等于可能建出重复订单。<b>中断也不返回 false</b>：那会把「线程被要求停下」
     * 和「暂时抢不到锁」混成同一件事，调用方据此提示用户"稍后再试"，
     * 实际上是该中止的操作被当成了业务繁忙。
     */
    public boolean tryLock(Long skuId) {
        RLock lock = getStockLock(skuId);
        try {
            return lock.tryLock(lockWaitSeconds, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取库存锁被中断", e);
        }
    }

    public void unlock(Long skuId) {
        RLock lock = getStockLock(skuId);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
