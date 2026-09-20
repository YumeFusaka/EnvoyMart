package yumefusaka.envoymart.productservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.productservice.model.ProductResponse;
import yumefusaka.envoymart.productservice.mq.CacheEvictConfig;
import yumefusaka.envoymart.productservice.mq.CacheEvictEvent;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 商品缓存服务 —— 读缓存、回源、回填，以及缓存三大问题的防护。
 * <p>
 * <b>为什么把 get 与 put 合成一个 getOrLoad</b>：三种防护都发生在"未命中之后、回源之前"
 * 这个窗口里，散在调用方写就必然有人漏写。收在一处，调用方只提供回源逻辑。
 *
 * <h3>三个问题的防护各自解决什么</h3>
 * <ul>
 *   <li><b>穿透</b>：查一个不存在的 id，缓存永远不命中、每次都打库。
 *       这里把"确认不存在"也缓存下来（空值哨兵），TTL 短得多——它挡的是重复穿透，
 *       不是在提供数据，所以不该占着 24 小时。</li>
 *   <li><b>雪崩</b>：大批 key 同一时刻失效，请求全砸到库上。
 *       写入时给 TTL 加随机抖动，把失效时刻打散。</li>
 *   <li><b>击穿</b>：单个热点 key 失效的瞬间，并发请求同时回源。
 *       用 SETNX 互斥，只让一个请求去重建，其余短暂等待后重读。</li>
 * </ul>
 * <p>
 * <b>为什么空值用哨兵对象而不是直接缓存 null</b>：Spring Data Redis 的序列化器
 * 把 null 与"键不存在"都返回成 null，两者在读取侧不可区分——那样空值缓存就失效了，
 * 每次都还是会走回源。
 */
@Slf4j
@Service
public class ProductCacheService {

    private static final String PRODUCT_KEY_PREFIX = "product:detail:";
    private static final String REBUILD_LOCK_PREFIX = "product:rebuild:";

    /** 基础 TTL：24 小时，实际写入时叠加随机抖动 */
    private static final long PRODUCT_TTL_HOURS = 24;
    /** 抖动上限（分钟）——把同一批写入的失效时刻打散，避免它们同时到期 */
    private static final long JITTER_MAX_MINUTES = 30;

    /** 空值缓存的 TTL：短得多，它只是挡住重复穿透 */
    private static final long NULL_TTL_MINUTES = 5;

    /** 重建锁的持有时间：够一次回源即可，太长会在回源失败时把后续请求也挡在门外 */
    private static final long REBUILD_LOCK_SECONDS = 10;
    /** 没抢到重建锁时的等待与重试：等别人填好缓存，而不是自己也去打库 */
    private static final long WAIT_FOR_REBUILD_MILLIS = 50;
    private static final int WAIT_RETRIES = 3;

    /**
     * 空值哨兵 —— 与"没缓存"区分开。
     * id 用 -1 而不是 null：序列化后仍是一个可识别的对象。
     */
    private static final ProductResponse NULL_SENTINEL = ProductResponse.builder().id(-1L).build();

    private final RedisTemplate<String, Object> redisTemplate;
    /** 删除失败时的补偿通道；没有它就只能靠 TTL 兜底 */
    private final RabbitTemplate rabbitTemplate;

    public ProductCacheService(RedisTemplate<String, Object> redisTemplate, RabbitTemplate rabbitTemplate) {
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 读缓存，未命中则回源并回填。
     *
     * @param loader 回源逻辑，商品不存在时返回 {@code null}
     * @return 商品；确认不存在时返回 {@code null}
     */
    public ProductResponse getOrLoad(Long id, Supplier<ProductResponse> loader) {
        ProductResponse cached = read(id);
        if (cached != null) {
            return cached;
        }
        // 缓存里明确标记过"不存在"：直接返回，不必回源
        if (isKnownMissing(id)) {
            return null;
        }

        if (!tryAcquireRebuildLock(id)) {
            // 别人正在重建：等它填好再读一次，而不是自己也去打库——
            // 这正是击穿防护的意义所在
            ProductResponse afterWait = waitForRebuild(id);
            if (afterWait != null || isKnownMissing(id)) {
                return afterWait;
            }
            // 等不到就自己回源，不让请求无限等下去
            log.debug("[Cache] 等待重建超时，自行回源: id={}", id);
        }

        try {
            ProductResponse loaded = loader.get();
            write(id, loaded);
            return loaded;
        } finally {
            releaseRebuildLock(id);
        }
    }

    /**
     * 商品变更后失效缓存。
     * <p>
     * <b>失败不向上抛</b>：这个方法的调用方是库存扣减与回补，而缓存是派生数据——
     * 它删不掉不该让库存操作跟着失败。原先直接调 {@code delete}，Redis 一抖动
     * 就是下单失败，代价与"缓存脏一会儿"完全不成比例。
     * <p>
     * 失败后落一条补偿消息，由消费者重试删除；连消息都发不出去时只能靠 TTL 兜底，
     * 但那条路径会留下 ERROR 日志，不是静默的。
     */
    public void evictProductCache(Long id) {
        try {
            deleteCacheOnly(id);
        } catch (Exception e) {
            log.error("[Cache] 删除缓存失败，转补偿队列重试: id={}", id, e);
            try {
                rabbitTemplate.convertAndSend(CacheEvictConfig.CACHE_EXCHANGE,
                        CacheEvictConfig.EVICT_KEY, new CacheEvictEvent(id, e.getMessage()));
            } catch (Exception mqEx) {
                log.error("[Cache] 补偿消息也发送失败，该条目只能等 TTL 自然过期: id={}", id, mqEx);
            }
        }
    }

    /**
     * 只删缓存，失败即抛。
     * <p>
     * 补偿消费者走这条路径而不是 {@link #evictProductCache}——后者在失败时会再发补偿消息，
     * 从消费者里调它就成了自我循环：一条删不掉的消息会无限复制自己。
     */
    public void deleteCacheOnly(Long id) {
        redisTemplate.delete(key(id));
    }

    // ==================== 读写 ====================

    private ProductResponse read(Long id) {
        Object cached = redisTemplate.opsForValue().get(key(id));
        if (cached == null || NULL_SENTINEL.equals(cached)) {
            return null;
        }
        return (ProductResponse) cached;
    }

    private boolean isKnownMissing(Long id) {
        // 必须用 equals 而不是 ==：从 Redis 读回来的是反序列化出的新对象，
        // 引用比较永远不成立——那样空值缓存会静默失效，穿透防护等于没做
        return NULL_SENTINEL.equals(redisTemplate.opsForValue().get(key(id)));
    }

    private void write(Long id, ProductResponse product) {
        if (product == null) {
            redisTemplate.opsForValue().set(key(id), NULL_SENTINEL, Duration.ofMinutes(NULL_TTL_MINUTES));
            return;
        }
        // TTL 加抖动：同一批写入的 key 不会在同一秒集体失效
        long jitterMinutes = ThreadLocalRandom.current().nextLong(JITTER_MAX_MINUTES + 1);
        redisTemplate.opsForValue().set(key(id), product,
                Duration.ofMinutes(PRODUCT_TTL_HOURS * 60 + jitterMinutes));
    }

    // ==================== 击穿防护 ====================

    private boolean tryAcquireRebuildLock(Long id) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(REBUILD_LOCK_PREFIX + id, "1", Duration.ofSeconds(REBUILD_LOCK_SECONDS)));
    }

    private void releaseRebuildLock(Long id) {
        redisTemplate.delete(REBUILD_LOCK_PREFIX + id);
    }

    private ProductResponse waitForRebuild(Long id) {
        for (int i = 0; i < WAIT_RETRIES; i++) {
            try {
                Thread.sleep(WAIT_FOR_REBUILD_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            ProductResponse cached = read(id);
            if (cached != null) {
                return cached;
            }
        }
        return null;
    }

    private String key(Long id) {
        return PRODUCT_KEY_PREFIX + id;
    }
}
