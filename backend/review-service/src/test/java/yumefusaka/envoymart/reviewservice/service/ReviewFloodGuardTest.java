package yumefusaka.envoymart.reviewservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同 (商品, IP) 短窗口计数的两条不变量。
 * <p>
 * 这两条错了都不会报错，只会让闸门悄悄失效或误伤：
 * <ul>
 *   <li><b>过期只在第一次设置</b> —— 每次 INCR 都 {@code expire} 会把窗口无限续命，
 *       持续刷评的人永远等不到窗口重置，与他「每 10 分钟最多 5 条」被揍成「一生最多 5 条」；</li>
 *   <li><b>Redis 不可用时返回「判不了」而不是「没超限」</b> —— 两者对调用方都是放行，
 *       但前者要留一条 WARN；合成一个 boolean 会让「限流器坏了」这件事没有任何痕迹。</li>
 * </ul>
 */
class ReviewFloodGuardTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ReviewFloodGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        guard = new ReviewFloodGuard(redis);
    }

    @Test
    void 第一次计数才设置过期() {
        AtomicLong counter = new AtomicLong();
        when(valueOps.increment(anyString())).thenAnswer(i -> counter.incrementAndGet());

        guard.recordAndCheck(1L, "10.0.0.1");
        guard.recordAndCheck(1L, "10.0.0.1");
        guard.recordAndCheck(1L, "10.0.0.1");

        // 只在第一次计数器为 1 时 expire；后续每次续期会把窗口变成「滑动且无限」
        verify(redis, org.mockito.Mockito.times(1)).expire(anyString(), eq(ReviewFloodGuard.WINDOW));
    }

    @Test
    void 没有IP或商品时判不了而不是判成没超限() {
        assertThat(guard.recordAndCheck(1L, null)).isEmpty();
        assertThat(guard.recordAndCheck(1L, "  ")).isEmpty();
        assertThat(guard.recordAndCheck(null, "10.0.0.1")).isEmpty();
        verify(redis, never()).opsForValue();
    }

    @Test
    void Redis异常时判不了而不是抛出() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("connection refused"));

        // 防刷的附加闸门：Redis 挂了不能连累正常评价写入
        assertThat(guard.recordAndCheck(1L, "10.0.0.1")).isEmpty();
    }

    @Test
    void 返回当前窗口内的计数() {
        AtomicLong counter = new AtomicLong();
        when(valueOps.increment(anyString())).thenAnswer(i -> counter.incrementAndGet());

        assertThat(guard.recordAndCheck(1L, "10.0.0.1")).isEqualTo(OptionalInt.of(1));
        assertThat(guard.recordAndCheck(1L, "10.0.0.1")).isEqualTo(OptionalInt.of(2));
    }

    @Test
    void 键里同时带商品与IP() {
        AtomicLong counter = new AtomicLong();
        when(valueOps.increment(anyString())).thenAnswer(i -> counter.incrementAndGet());

        guard.recordAndCheck(7L, "10.0.0.1");

        // 维度必须是 (商品, IP)：只按 IP 会让同一个出口 IP 下不同商品的正常评价互相拖累，
        // 只按商品则退化成全站共享一个计数器
        verify(valueOps).increment("review:flood:spu-ip:7:10.0.0.1");
    }
}
