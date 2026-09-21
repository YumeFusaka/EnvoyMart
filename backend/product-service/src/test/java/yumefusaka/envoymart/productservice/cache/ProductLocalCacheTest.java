package yumefusaka.envoymart.productservice.cache;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.productservice.model.ProductResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一级缓存（进程内）的行为约束。
 * <p>
 * 这里只测<b>单实例内</b>的读写与失効——跨实例失效靠 Redis 广播，
 * 必须起两个实例才能验，用单测模拟它等于测了个假的（见 docs/项目总览.md 的验证记录）。
 */
class ProductLocalCacheTest {

    private ProductLocalCache newCache(long maxSize, long ttlSeconds) {
        return new ProductLocalCache(maxSize, ttlSeconds);
    }

    private ProductResponse product(long id) {
        return ProductResponse.builder().id(id).name("p" + id).stock(1).build();
    }

    @Test
    void 写入后可读到同一份数据() {
        ProductLocalCache cache = newCache(100, 60);
        cache.put(1L, product(1));

        assertThat(cache.get(1L)).isNotNull();
        assertThat(cache.get(1L).getId()).isEqualTo(1L);
        assertThat(cache.get(2L)).as("没写过的 key 应为空").isNull();
    }

    @Test
    void 失效后立即读不到() {
        ProductLocalCache cache = newCache(100, 60);
        cache.put(1L, product(1));
        cache.invalidate(1L);

        // 这是跨实例广播要调的方法：**"失效后必须立刻读不到"是它唯一的语义**，
        // 一旦这里变成"延迟生效"，别的实例就会返回旧库存
        assertThat(cache.get(1L)).isNull();
    }

    @Test
    void 不缓存null_空值哨兵只留在Redis侧() {
        ProductLocalCache cache = newCache(100, 60);
        cache.put(1L, null);

        // "确认不存在"这条信息走 Redis 的空值哨兵，不进本地缓存：
        // 本地缓存是每实例一份，把空值也复制一份不划算，而且它本来就该由布隆过滤器先拦一道
        assertThat(cache.get(1L)).isNull();
        assertThat(cache.estimatedSize()).isZero();
    }

    @Test
    void null的id不参与读写() {
        ProductLocalCache cache = newCache(100, 60);
        cache.put(null, product(1));

        assertThat(cache.get(null)).isNull();
        assertThat(cache.estimatedSize()).isZero();
    }

    @Test
    void 超过容量上限后按淘汰策略丢弃_不会无限增长() {
        ProductLocalCache cache = newCache(10, 3600);
        for (long id = 1; id <= 100; id++) {
            cache.put(id, product(id));
        }
        // 淘汰是异步的，先结算一次再断言，否则测的是"异步任务有没有跑"而不是"有没有上限"
        cache.cleanUp();

        // **有界性是安全要求，不是优化**：商品 id 来自外部输入，
        // 不设上限就是一条"用不同 id 把堆撑爆"的路径。
        assertThat(cache.estimatedSize())
                .as("本地缓存没有上限，堆会被外部输入撑爆")
                .isLessThanOrEqualTo(10L);
    }

    @Test
    void 写入后超过TTL即失效_这是广播丢失时的兜底() {
        ProductLocalCache cache = newCache(100, 1);
        cache.put(1L, product(1));
        assertThat(cache.get(1L)).isNotNull();

        // TTL 是**兜底而不是主要手段**：广播丢了、实例断连了，最多旧这么久。
        // 没有它，本地缓存就变成一个永不收敛的旧值副本。
        sleepQuietly(1200);
        assertThat(cache.get(1L)).as("超过 TTL 后仍能读到，说明兜底失效").isNull();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
