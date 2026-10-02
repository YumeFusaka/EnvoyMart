package yumefusaka.envoymart.productservice.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.contract.ProductDetail;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一级缓存（进程内，Caffeine）—— 挡在 Redis 前面。
 *
 * <h3>为什么还要在 Redis 前面加一层</h3>
 * 热点商品的详情页 QPS 很高，而每次查 Redis 都是一次网络往返（同一机房约 0.5~1ms）。
 * 本地内存命中是纳秒级。对真正的热点 key，这一层把 Redis 的读压力直接砍掉。
 *
 * <h3>代价：一致性，而且必须正面处理</h3>
 * 本地缓存的失效**不能只删 Redis**——别的实例手里还有各自的副本。
 * 所以失效走广播（见 {@code ProductCacheInvalidationConfig}），
 * 且这里的 TTL 是**兜底而不是主要手段**：广播丢了、实例断连了，
 * 最多旧 {@code ttl-seconds} 秒。**不做广播、只靠 TTL，等于把"缓存"变成"有延迟的随机值"**，
 * 而商品详情里带库存、前端拿它做加购联动，旧库存在这个场景里不是无关紧要的字段。
 *
 * <h3>有界性</h3>
 * {@code maximumSize} 按数量淘汰（W-TinyLFU，命中率优于 LRU）。
 * **不加界就是内存泄漏**：商品 id 是外部输入，攻击者用不同 id 就能把堆撑爆——
 * 虽然布隆过滤器在前面挡了绝大部分，但这一层不该依赖上游来保证自己的安全性。
 */
@Slf4j
@Component
public class ProductLocalCache {

    /**
     * 缓存实例。
     * <p>
     * <b>存的是响应 DTO，调用方只读不写</b>：所有读取路径都只取字段。
     * 一旦有人拿到后修改它，改的是缓存里那一份、会影响后续所有请求——
     * 这个约束靠约定维持，DTO 本身没有做成不可变对象。
     */
    private final Cache<Long, ProductDetail> cache;

    /**
     * 失效纪元：任何一次 {@link #invalidate} 都会 +1，供回填方判断
     * "我读这份数据期间，有没有发生过失效"。
     */
    private final AtomicLong invalidationEpoch = new AtomicLong();

    /** 让"校验纪元 + 写入"与"递增纪元 + 失效"互斥，见 {@link #fillIfNoInvalidationSince}。 */
    private final Object fillLock = new Object();

    public ProductLocalCache(
            @Value("${envoymart.cache.local.max-size:10000}") long maxSize,
            @Value("${envoymart.cache.local.ttl-seconds:60}") long ttlSeconds) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .build();
        log.info("[LocalCache] 一级缓存已启用：最大 {} 条、兜底 TTL {}s", maxSize, ttlSeconds);
    }

    public ProductDetail get(Long id) {
        return id == null ? null : cache.getIfPresent(id);
    }

    /** 读取当前纪元，作为随后那次回填的"读起点"。 */
    public long invalidationEpoch() {
        return invalidationEpoch.get();
    }

    /**
     * 读期间没发生过失效才回填 —— 挡住"旧值被钉回缓存"的竞态。
     * <p>
     * <b>竞态长什么样</b>：读路径先查缓存拿到旧值 V，准备回填本地；与此同时写入方
     * 提交了新值并让缓存失效（删 Redis、广播、清本地）。若回填无条件执行，V 就又回到了
     * 本地缓存里——**刚被删掉的值被自己钉了回去**，此后直到本地 TTL（默认 60s）
     * 或下一次失效为止，这个实例一直返回旧值。窗口通常只有微秒级，但一次 GC 停顿
     * 就能把它拉大到几十毫秒，观感是"改动生效了、只不过某台机器上要等一分钟后才生效"。
     * <p>
     * <b>为什么纪元是全局而不是按 id</b>：按 id 要维护一张会增长的计数表，而失效本身
     * 不频繁——全局纪元最坏只是把"另一个商品的失效"误判成"不能回填"，代价是这次少一次
     * 本地缓存命中，不会读到错误数据。宁可少回填，不可错回填。
     *
     * @param epochAtRead 发起读取前通过 {@link #invalidationEpoch()} 取得的纪元
     * @return 是否允许回填；{@code false} 表示期间发生过失效，调用方应放弃这次回填
     *         （Redis 那边同理：写回 Redis 也可能把刚删的旧值灌回去）
     */
    public boolean fillIfNoInvalidationSince(Long id, ProductDetail product, long epochAtRead) {
        synchronized (fillLock) {
            if (invalidationEpoch.get() != epochAtRead) {
                return false;
            }
            if (id != null && product != null) {
                cache.put(id, product);
            }
            return true;
        }
    }

    /**
     * 失效单个商品 —— 本地直接删，再广播给其他实例。
     * <p>
     * **本实例不等广播回来**：发布者自己也要立刻一致，依赖"pub/sub 会回显给自己"
     * 是把正确性押在中间件的行为细节上。
     * <p>
     * 递增纪元与删除在同一把锁里完成，保证与 {@link #fillIfNoInvalidationSince}
     * 的全序：任何一次失效，要么发生在回填校验之前（回填被拒），要么之后（回填的值当场被删）。
     */
    public void invalidate(Long id) {
        synchronized (fillLock) {
            invalidationEpoch.incrementAndGet();
            if (id != null) {
                cache.invalidate(id);
            }
        }
    }

    /** 供健康检查与压测观察。 */
    public long estimatedSize() {
        return cache.estimatedSize();
    }

    /**
     * 立即结算一次淘汰维护。
     * <p>
     * Caffeine 的淘汰与过期清理是<b>异步</b>的，{@code estimatedSize()} 会把"待淘汰"的
     * 条目也算进去。所以这个方法的用途有两个：测试里要一个确定性的观察点；
     * 运维排查"内存为什么没降下来"时，需要一个立即结算的入口。
     */
    public void cleanUp() {
        cache.cleanUp();
    }
}
