package yumefusaka.envoymart.orderservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.orderservice.model.CartItemResponse;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 缓存包装层：购物车缓存 & 分布式锁
 */
@Slf4j
@Service
public class CartCacheService {

    /** 缓存是否不可用，用来把故障日志压成"状态翻转时一次"（见 reportCacheFailure） */
    private final AtomicBoolean cacheUnavailable = new AtomicBoolean(false);

    /** 熔断到期时间戳（毫秒）。now 小于它 = 处于熔断中，跳过缓存直接查库。 */
    private final AtomicLong cacheCircuitOpenUntil = new AtomicLong(0L);

    /**
     * 缓存故障后的熔断冷却时长。
     * <p>
     * **没有它，降级就是假的**：一次请求要撞多次 Redis、每次各等满超时——
     * 实测购物车在 Redis 挂掉后要 10 秒才返回。熔断后只有撞上的那一次付超时成本。
     */
    private static final long CACHE_CIRCUIT_COOLDOWN_MILLIS = 3_000;

    private static final String CART_KEY_PREFIX = "cart:user:";
    private static final String STOCK_LOCK_PREFIX = "stock:lock:";
    private static final long CART_TTL_HOURS = 72;
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

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    public CartCacheService(RedisTemplate<String, Object> redisTemplate,
                            RedissonClient redissonClient,
                            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
    }

    // ========== 购物车缓存 ==========

    /**
     * 缓存载荷 —— 存在的唯一理由是「给列表套一个具体类型的壳」。
     * <p>
     * 不能直接缓存 {@code List<CartItemResponse>}：序列化器开了 defaultTyping，
     * 写<b>单个对象</b>时会带上根类型的 {@code @class}，写 <b>List</b> 时却不会——
     * 而读取端的目标类型是 {@code Object}，碰到以 {@code [} 开头的 JSON 就要求根类型 id，
     * 于是抛 {@code Unexpected token (END_ARRAY), expected VALUE_STRING}。
     * <p>
     * 失败发生在<b>读取侧</b>：写入一声不吭，读的时候 100% 挂。表现为购物车「第一次能用、
     * 刷新就 500」，且加购/改数量/下单都会 evict 缓存，于是现象变成一次好一次坏地交替，
     * 很容易被当成偶发抖动。包一层具体类型后根类型 id 会被正常写入，往返一致。
     */
    public record CachedCart(List<CartItemResponse> items) {
    }

    /**
     * 读购物车缓存。**Redis 不可用时降级为空**，由调用方回源数据库。
     * <p>
     * 返回空列表而不是抛异常，是因为调用方 {@code listCartItems} 本来就是
     * 「缓存没有就查库」的写法——空 = 未命中，正好落到那条路径上。
     * <p>
     * <b>注意与下单路径的区别</b>：这里可以降级，但 {@link #tryLock} 不行。
     * 读可以退回数据库，而"下单要不要拿锁"没有降级选项——放行等于可能建出重复订单。
     */
    public List<CartItemResponse> getCachedCart(String userId) {
        if (!cacheUsable()) {
            return Collections.emptyList();
        }
        try {
            Object cached = redisTemplate.opsForValue().get(cartKey(userId));
            if (cached instanceof CachedCart cart) {
                return cart.items();
            }
            reportCacheRecovered();
        } catch (Exception e) {
            reportCacheFailure("getCachedCart", e);
        }
        return Collections.emptyList();
    }

    /** 回填失败只意味着"下次还得查库"，数据已经在手上，不该影响本次返回。 */
    public void cacheCart(String userId, List<CartItemResponse> items) {
        if (!cacheUsable()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(cartKey(userId),
                    new CachedCart(List.copyOf(items)), CART_TTL_HOURS, TimeUnit.HOURS);
            reportCacheRecovered();
        } catch (Exception e) {
            reportCacheFailure("cacheCart", e);
        }
    }

    /**
     * 缓存故障的日志按<b>状态翻转</b>记，而不是每条一次。
     * <p>
     * Redis 长时间不可用时会持续抛异常，逐条记会把日志刷爆、把真正的信号淹掉。
     * 挂的那一下记 ERROR、恢复的那一下记 INFO，中间保持安静。
     */
    private void reportCacheFailure(String op, Exception e) {
        // 打开熔断，冷却期内不再尝试 Redis
        cacheCircuitOpenUntil.set(System.currentTimeMillis() + CACHE_CIRCUIT_COOLDOWN_MILLIS);
        if (cacheUnavailable.compareAndSet(false, true)) {
            log.error("[CartCache] Redis 不可用，缓存已降级（{}ms 后重试，成功则自动切回）: op={} err={}",
                    CACHE_CIRCUIT_COOLDOWN_MILLIS, op,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 熔断期内一律跳过缓存 —— 这是"降级"与"慢速失败"的区别所在。 */
    private boolean cacheUsable() {
        return System.currentTimeMillis() >= cacheCircuitOpenUntil.get();
    }

    private void reportCacheRecovered() {
        cacheCircuitOpenUntil.set(0L);
        if (cacheUnavailable.compareAndSet(true, false)) {
            log.info("[CartCache] Redis 已恢复，重新启用缓存");
        }
    }

    public void evictCartCache(String userId) {
        redisTemplate.delete(cartKey(userId));
    }

    // ========== 分布式锁 ==========

    public RLock getStockLock(Long productId) {
        return redissonClient.getLock(STOCK_LOCK_PREFIX + productId);
    }

    /**
     * 尝试加分布式锁：抢到返回 true，等待超时返回 false。
     * <p>
     * 中断不返回 false——那会把「线程被要求停下」和「暂时抢不到锁」混成同一件事，
     * 调用方据此提示用户"稍后再试"，实际上是该中止的操作被当成了业务繁忙。
     */
    public boolean tryLock(Long productId) {
        RLock lock = getStockLock(productId);
        try {
            return lock.tryLock(lockWaitSeconds, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取库存锁被中断", e);
        }
    }

    public void unlock(Long productId) {
        RLock lock = getStockLock(productId);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private String cartKey(String userId) {
        return CART_KEY_PREFIX + userId;
    }
}
