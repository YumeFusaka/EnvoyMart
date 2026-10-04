package yumefusaka.envoymart.reviewservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.reviewservice.client.OrderClient;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 新账号保护期的三条边界。
 * <p>
 * 判据是「本次收货距该用户最早一次收货不满 7 天」。三条最容易写错的：
 * <ul>
 *   <li><b>判的是账号年龄，不是这一单的新旧</b> —— 对照基准是本次收货时间与最早收货时间之差。
 *       写成「本次收货距 now() 不满 7 天」会把老客户刚收货的新单一起标成待审；</li>
 *   <li><b>从未有过收货</b> —— {@code firstReceivedAt} 返回 null。这不是「判不了」，
 *       是「他的人生第一单」，必须进待审；写成 null 就放行，等于整个判据对最需要拦的人失效；</li>
 *   <li><b>判不了时放行</b> —— 订单服务抖动不能让一次网络故障看起来像一次风控命中。</li>
 * </ul>
 */
class ReviewNewAccountGuardTest {

    private OrderClient orderClient;
    private ReviewNewAccountGuard guard;

    @BeforeEach
    void setUp() {
        orderClient = mock(OrderClient.class);
        guard = new ReviewNewAccountGuard(orderClient);
    }

    @Test
    void 账号年龄在保护期内要进待审() {
        LocalDateTime first = LocalDateTime.of(2026, 10, 1, 10, 0);
        when(orderClient.firstReceivedAt("u1")).thenReturn(Result.success(first));

        // 同一单收货当天就评价：账号年龄 0 天，在保护期内
        assertThat(guard.shouldHoldForReview("u1", first)).isTrue();
        // 第 6 天仍在保护期内
        assertThat(guard.shouldHoldForReview("u1", first.plusDays(6))).isTrue();
    }

    @Test
    void 账号年龄超过保护期按老账号处理() {
        LocalDateTime first = LocalDateTime.of(2026, 10, 1, 10, 0);
        when(orderClient.firstReceivedAt("u1")).thenReturn(Result.success(first));

        assertThat(guard.shouldHoldForReview("u1", first.plusDays(7))).isFalse();
        assertThat(guard.shouldHoldForReview("u1", first.plusDays(30))).isFalse();
    }

    @Test
    void 老账号刚收货的新单不进待审() {
        // 半年前就在平台上买东西的老客户
        LocalDateTime first = LocalDateTime.of(2026, 4, 1, 10, 0);
        when(orderClient.firstReceivedAt("u6")).thenReturn(Result.success(first));

        // 今天刚收到的一单：这一单很新，但账号年龄 180+ 天。判的是账号年龄，不是这一单，
        // 所以必须放行 —— 否则每个老客户的新单评价都会被标成待审，队列被淹
        assertThat(guard.shouldHoldForReview("u6", first.plusDays(180))).isFalse();
    }

    @Test
    void 从未有过收货的用户进待审() {
        when(orderClient.firstReceivedAt("u2")).thenReturn(Result.success(null));

        // 「第一单」不是「判不了」：这正是要防的批量小号形态
        assertThat(guard.shouldHoldForReview("u2", LocalDateTime.now())).isTrue();
    }

    @Test
    void 下游失败时放行而不是转待审() {
        when(orderClient.firstReceivedAt(anyString())).thenThrow(new RuntimeException("order-service down"));

        // 待审是面向人的队列，不该被机器故障灌满
        assertThat(guard.shouldHoldForReview("u3", LocalDateTime.now())).isFalse();
    }

    @Test
    void 业务码非200时放行() {
        Result<LocalDateTime> failed = new Result<>();
        failed.setCode(500);
        when(orderClient.firstReceivedAt("u4")).thenReturn(failed);

        assertThat(guard.shouldHoldForReview("u4", LocalDateTime.now())).isFalse();
    }

    @Test
    void 对照本次收货时间而不是当前时间() {
        LocalDateTime first = LocalDateTime.of(2026, 4, 1, 10, 0);
        when(orderClient.firstReceivedAt("u5")).thenReturn(Result.success(first));

        // 管理端补录一条 2026-04-03 收货的历史评价：相对「首次收货」只过了 2 天，
        // 账号当时还在保护期内。改成对照 now() 的话它会被判成老账号 ——
        // 判据要对齐本次收货那一刻，而不是「执行这条代码的今天」
        assertThat(guard.shouldHoldForReview("u5", LocalDateTime.of(2026, 4, 3, 10, 0))).isTrue();
    }
}
