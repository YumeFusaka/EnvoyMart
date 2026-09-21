package yumefusaka.envoymart.productservice.cache;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 布隆过滤器的行为约束。
 * <p>
 * 重点不在"能判对"，而在三条**失效模式**上——它们比功能本身更容易出事：
 * 假阴性会让正常商品查不到、未加载会拦掉全站流量、误判率失控则等于没做。
 */
class ProductBloomFilterTest {

    @Test
    void 已加入的id绝不返回不存在() {
        ProductBloomFilter filter = new ProductBloomFilter();
        List<Long> ids = LongStream.rangeClosed(1, 1000).boxed().toList();
        filter.rebuild(ids);

        // 布隆过滤器的**唯一硬保证**：不存在假阴性。
        // 一旦这里失守，用户会看到"商品不存在"——而商品明明在库里。
        assertThat(ids).allSatisfy(id ->
                assertThat(filter.mightContain(id)).as("id=%d 必须判为可能存在", id).isTrue());
    }

    @Test
    void 未加载时一律放行_绝不能把正常流量拦掉() {
        ProductBloomFilter filter = new ProductBloomFilter();

        // 这是最危险的一种失效：过滤器还没加载（或加载失败）时位数组是空的，
        // 此时"严格判定"会把**所有**商品都判成不存在，全站详情页瞬间不可用。
        // 一个防穿透的组件把正常流量全拦了，比没有它更糟。
        assertThat(filter.isLoaded()).isFalse();
        assertThat(filter.mightContain(1L)).isTrue();
        assertThat(filter.mightContain(999_999_999L)).isTrue();
    }

    @Test
    void id为null时放行而不是判不存在() {
        ProductBloomFilter filter = new ProductBloomFilter();
        filter.rebuild(List.of(1L, 2L, 3L));

        assertThat(filter.mightContain(null)).isTrue();
    }

    @Test
    void 重建后只认新的集合() {
        ProductBloomFilter filter = new ProductBloomFilter();
        filter.rebuild(List.of(1L, 2L, 3L));
        assertThat(filter.size()).isEqualTo(3);

        // 重建必须把旧位清干净，否则过滤器会随重建次数单调变松、误判率一路升高
        filter.rebuild(List.of(100L, 200L));
        assertThat(filter.size()).isEqualTo(2);
        assertThat(filter.mightContain(100L)).isTrue();
        assertThat(filter.mightContain(200L)).isTrue();
    }

    @Test
    void 误判率不超过设定的量级() {
        ProductBloomFilter filter = new ProductBloomFilter();
        List<Long> ids = LongStream.rangeClosed(1, 10_000).boxed().toList();
        filter.rebuild(ids);

        // 拿 10 万个**确定不存在**的 id 去问，统计被误判成"可能存在"的比例。
        // 设定值 1%，这里放宽到 3% —— 测的是"没有失控"，不是精确复现理论值
        // （理论值依赖哈希质量，测得太紧会因为实现细节而 flaky）。
        long falsePositives = LongStream.rangeClosed(1_000_001, 1_100_000)
                .filter(filter::mightContain)
                .count();

        double rate = falsePositives / 100_000.0;
        assertThat(rate)
                .as("误判率 %.4f 超出量级，说明哈希或位数组大小算错了", rate)
                .isLessThan(0.03);
    }
}
