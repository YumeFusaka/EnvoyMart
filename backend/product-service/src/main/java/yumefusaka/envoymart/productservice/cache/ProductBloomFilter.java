package yumefusaka.envoymart.productservice.cache;

import lombok.extern.slf4j.Slf4j;

import java.util.BitSet;
import java.util.List;

/**
 * 商品 ID 布隆过滤器 —— 缓存穿透的<b>第一道</b>防线。
 *
 * <h3>它挡的是空值哨兵挡不住的那一类</h3>
 * 空值哨兵缓存的是"某个 id 不存在"，对**重复**查同一个不存在的 id 有效；
 * 但攻击者用**随机 id** 时，每次都是新 key、永远不命中，空值哨兵形同虚设——
 * 每个请求依然会打一次数据库。
 *
 * 布隆过滤器反过来：它记录的是"**哪些 id 存在**"，所以能对任意 id 直接回答
 * 「一定不存在」。随机扫描在这层就被挡住，一次数据库都不会碰。
 *
 * <h3>为什么不用 Guava</h3>
 * 不是为了省一个依赖，而是**分布式下本地过滤器才需要这么大费周章**——
 * 见下面「它不能做什么」的第 2 条。Guava 的 {@code BloomFilter} 语义完全一样，
 * 换成它只需替换本类的两个方法。
 *
 * <h3>参数怎么定的</h3>
 * 按<b>生产规模</b>而不是本项目的种子数据量定：100 万商品、1% 误判率。
 * 位数组约 1.2 MB、7 个哈希函数——用 1.2 MB 换掉绝大部分穿透查询，是划算的。
 * 按开发环境的几条数据定参数，等于没做设计。
 *
 * <h3>它不能做什么（必须一起说清）</h3>
 * <ol>
 *   <li><b>有假阳性</b>：说"可能存在"时不一定真的存在，所以它后面仍要有空值哨兵兜底。
 *       反过来说「一定不存在」是可靠的，这正是它的价值。</li>
 *   <li><b>不支持删除</b>：清掉一个 id 的位会影响其他 id。商品下架罕见的场景可以接受；
 *       真要支持就得用计数布隆过滤器或定期重建。</li>
 *   <li><b>新增商品必须同步</b>，否则新商品的合法查询会被误判成"不存在"——
 *       这是它最危险的失效方式，<b>所以下面加了"未加载就不拦"的保护</b>。</li>
 * </ol>
 */
@Slf4j
public class ProductBloomFilter {

    /** 预计容量：按生产规模取值，不是当前种子数据的条数 */
    private static final int EXPECTED_INSERTIONS = 1_000_000;
    /** 可接受的误判率 */
    private static final double FALSE_POSITIVE_RATE = 0.01;

    /** 位数组长度 m = -n·ln(p) / (ln2)² */
    private static final int BIT_SIZE = (int) Math.ceil(
            -EXPECTED_INSERTIONS * Math.log(FALSE_POSITIVE_RATE) / (Math.log(2) * Math.log(2)));
    /** 哈希函数个数 k = (m/n)·ln2 */
    private static final int HASH_COUNT = (int) Math.round((double) BIT_SIZE / EXPECTED_INSERTIONS * Math.log(2));

    private final BitSet bits = new BitSet(BIT_SIZE);
    /** 全部商品 id 的集合，仅用于日志与自检（不参与判定） */
    private volatile int size = 0;
    /**
     * 是否已经加载过。
     * <p>
     * <b>这个标志是必需的，不是保险起见</b>：过滤器为空时 {@code mightContain} 会返回
     * false，也就是"所有商品都不存在"——如果不加这道保护，加载失败或还没加载完时，
     * 全站商品详情会瞬间全部返回"商品不存在"。**一个防穿透的组件把正常流量全拦了，
     * 比没有它更糟。** 所以未加载时一律放行。
     */
    private volatile boolean loaded = false;

    static {
        log.info("[Bloom] 参数：预计容量={} 误判率={} → 位数组={}位({}KB) 哈希={}个",
                EXPECTED_INSERTIONS, FALSE_POSITIVE_RATE,
                BIT_SIZE, BIT_SIZE / 8 / 1024, HASH_COUNT);
    }

    /**
     * 用全量商品 id 重建过滤器。
     * <p>
     * 加锁读写在重建期间是不必要的：{@link BitSet} 的 set/get 是原子的，
     * 重建过程中最坏情况是少数 id 还没置位、被误判为"不存在"——
     * 而那正是下一条要防的：<b>重建期间先关掉 {@code loaded}</b>，
     * 让判定退化为"放行"，重建完再打开。
     */
    public synchronized void rebuild(List<Long> productIds) {
        loaded = false;
        try {
            bits.clear();
            size = 0;
            for (Long id : productIds) {
                if (id != null) {
                    add(id);
                }
            }
            loaded = true;
            log.info("[Bloom] 已加载 {} 个商品 id", size);
        } catch (Exception e) {
            // 加载失败保持 loaded=false（放行），绝不留下一个"空过滤器"去拦正常流量
            log.error("[Bloom] 加载失败，过滤器将不参与判定（穿透防护退化为仅空值哨兵）", e);
        }
    }

    public synchronized void add(Long productId) {
        if (productId == null) {
            return;
        }
        for (int i = 0; i < HASH_COUNT; i++) {
            bits.set(hash(productId, i));
        }
        size++;
    }

    /**
     * 判断商品 id <b>是否可能存在</b>。
     *
     * @return false 表示<b>一定不存在</b>（可以直接拒绝，不必查缓存与数据库）；
     *         true 表示可能存在，仍需走正常查询流程
     */
    public boolean mightContain(Long productId) {
        if (!loaded || productId == null) {
            // 未加载 = 不参与判定，一律放行（见 loaded 的注释）
            return true;
        }
        for (int i = 0; i < HASH_COUNT; i++) {
            if (!bits.get(hash(productId, i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 由一对哈希派生出 k 个位置（Kirsch-Mitzenmacher）。
     * <p>
     * 不必真的算 k 个独立哈希——用两个独立哈希 {@code h1 + i·h2} 组合，
     * 误判率与 k 个独立哈希几乎一致，而计算量只有它的 1/k。
     */
    private int hash(Long productId, int index) {
        long h1 = mix(productId);
        long h2 = mix(productId ^ 0x9E3779B97F4A7C15L);
        long combined = h1 + (long) index * h2;
        return (int) Math.floorMod(combined, BIT_SIZE);
    }

    /** 混淆函数（splitmix64 的收尾部分）：把连续的自增 id 打散，否则相邻 id 的哈希也相邻 */
    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 供健康检查与测试观察当前状态。 */
    public int size() {
        return size;
    }

    public boolean isLoaded() {
        return loaded;
    }
}
