package yumefusaka.envoymart.reviewservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.OptionalInt;

/**
 * 设备 / IP 维度的短窗口评价计数 —— 刷评治理的第二道闸。
 * <p>
 * <b>它挡的是「每日 5 条」挡不住的那一类</b>：那条闸以 {@code user_id} 为维度，
 * 拦得住单个账号，拦不住批量注册的小号。同一台机器 / 同一个出口 IP 在短窗口内
 * 冒出几十条评价，是刷评最典型、也最容易观察到的信号 —— 而它不需要任何用户数据。
 * <p>
 * <b>为什么是 Redis 而不是数据库</b>：评价表里根本没有 IP 这一列，现算无从谈起；
 * 而这种「短窗口、可过期、只用于限流」的计数正是 Redis 的用法。窗口短到
 * 不会误伤正常用户（一个真人不会在同一分钟里对同一商品写好几次评价）。
 * <p>
 * <b>失败立场是 fail-open</b>：Redis 不可用时放行。这是防刷的<b>附加</b>闸门，
 * 拿可用性换它不划算；真正兜底的是「必须有已收货订单」与「每日 5 条」。
 * 代价说清楚：Redis 抖动期间这道闸形同不存在，但不会让正常用户写不了评价。
 */
@Slf4j
@Component
public class ReviewFloodGuard {

    /**
     * 同一 (商品, IP) 在窗口内允许的评价条数。
     * <p>
     * 5 这个数的依据：一个真人同一件商品只会评一次（订单行唯一约束保证），
     * 同一 IP 下一天内对<b>同一商品</b>出现 5 条以上，几乎不可能是各自独立的买家 ——
     * 家庭 / 公司共用一个出口 IP 通常也就两三条。这是「明显异常」的下沿，
     * 不做精细化调参：阈值定得越低误伤越多，而这道闸本来就不是唯一防线。
     */
    public static final int MAX_SAME_SPU_PER_IP = 5;

    /** 窗口长度。短窗口才叫「刷」——一天里分散开的评价交给每日条数闸 */
    static final Duration WINDOW = Duration.ofMinutes(10);

    private static final String KEY_PREFIX = "review:flood:spu-ip:";

    private final StringRedisTemplate redis;

    public ReviewFloodGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 记一次并判断是否超限。
     * <p>
     * <b>返回空表示「判不了」</b>（Redis 不可用或拿不到 IP），与「没超限」是两回事：
     * 调用方对前者的处理是放行 + 记一条 WARN，对后者是静默放行。
     * 把两者合并成一个 boolean 会让「限流器坏了」这件事没有任何痕迹。
     *
     * @return 空 = 判不了（放行）；否则为当前窗口内的计数
     */
    public OptionalInt recordAndCheck(Long spuId, String clientIp) {
        if (spuId == null || clientIp == null || clientIp.isBlank()) {
            return OptionalInt.empty();
        }
        try {
            String key = KEY_PREFIX + spuId + ":" + clientIp;
            // INCR 是原子的：先查再写会让并发的几次请求读到同一个旧值，
            // 于是「已经超了」被一起漏过去
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 只在第一次设置过期：每次都 set 会把窗口无限延长，
                // 持续刷评的人永远等不到窗口重置（这与「今天已发 N 条」的语义相反）
                redis.expire(key, WINDOW);
            }
            return count == null ? OptionalInt.empty() : OptionalInt.of(count.intValue());
        } catch (RuntimeException e) {
            log.warn("[Review] 同商品 IP 计数不可用，本次放行（fail-open）: spuId={} ip={} reason={}",
                    spuId, clientIp, e.getMessage());
            return OptionalInt.empty();
        }
    }
}
