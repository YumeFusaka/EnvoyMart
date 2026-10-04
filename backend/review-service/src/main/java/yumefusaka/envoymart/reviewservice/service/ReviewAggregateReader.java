package yumefusaka.envoymart.reviewservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.reviewservice.mapper.ReviewMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 已发布评价的聚合读数 —— 「这个商品现在几分、几条」的<b>唯一</b>算法。
 * <p>
 * <b>为什么单独抽出来</b>：这个值有两个消费方，一是商品详情页的评分区，二是回写给
 * product-service 的 {@code rating_avg / review_count}。两处各算一遍的话，
 * 用户会看到「评价区写 4.8 分、商品标题旁边写 4.7 分」——而且两边单独看都自洽，
 * 只有把它们放进同一个页面才看得出来。
 * <p>
 * 不能直接把这个方法挂在 {@code ReviewServiceImpl} 上让发布器调用：发布器被
 * {@code ReviewServiceImpl} 注入，反过来再注入它就是构造器循环依赖，Spring 起不来。
 */
@Slf4j
@Component
public class ReviewAggregateReader {

    /**
     * 原子地推进版本号：{@code version = max(现有值, 本次最大评价 id) + 1}。
     * <p>
     * {@code KEYS[1]} 计数器键，{@code ARGV[1]} 本次聚合覆盖到的最大评价 id。
     */
    private static final String LUA_NEXT_VERSION =
            "local cur = tonumber(redis.call('GET', KEYS[1]) or '0') "
            + "local base = tonumber(ARGV[1]) or 0 "
            + "local next = math.max(cur, base) + 1 "
            + "redis.call('SET', KEYS[1], next) "
            + "return next";

    private final ReviewMapper reviewMapper;
    private final StringRedisTemplate redis;

    public ReviewAggregateReader(ReviewMapper reviewMapper, StringRedisTemplate redis) {
        this.reviewMapper = reviewMapper;
        this.redis = redis;
    }

    /**
     * @param total        已发布评价条数
     * @param average      平均分，一位小数（展示口径），第二位补零；没有评价时为 {@code 0.00}
     * @param distribution 各星级条数，下标 0 是 1 星
     * @param version      这份快照的版本号（单调递增，删除评价后也不回退）；
     *                     与全量值配套消掉「乱序覆盖」——消费者只接受更大的版本
     */
    public record Snapshot(long total, BigDecimal average, List<Long> distribution, long version) {
    }

    /**
     * 生成这份快照的版本号——**单调递增，且删除评价后也不回退**。
     * <p>
     * 原先版本取「本次聚合覆盖到的最大评价 id」。那个值在**删除**评价时会回退：
     * 脚本批量删掉测试评价后，权威值是 72，而商品侧已记着 183，
     * 于是之后所有快照都被消费者判成「旧的」丢掉，商品侧评分永久停在错值上。
     * <p>
     * 改用 Redis 计数器：它只增不减，删除评价不影响它。再与「最大评价 id」取大，
     * 是为了覆盖 Redis 被清空/首次部署的场景——那时序列从 0 起，而库里已有历史评价，
     * 取大能立刻回到正确的量级，不会因为一次缓存清空就把评分卡住。
     * <p>
     * fail-open：Redis 不可用时退回「最大评价 id」。那会让删除后的场景重新失效，
     * 但它退化的方向是「评分可能停在旧值」而不是「读写路径被打断」——
     * 而这个类的两个消费方（商品详情、聚合回写）都不该被一个加速层拖垮。
     */
    private long nextVersion(Long spuId, long maxReviewId) {
        String key = "review:aggregate:version:" + spuId;
        try {
            // 用一段 Lua 原子地做「只抬高、然后 +1」：
            //   version = max(现有值, 本次最大评价 id) + 1
            // 不能用「GET → 比大小 → SET」在 Java 侧拼，那在并发下两个线程会读到同一个旧值，
            // 各自算出同一个版本号——商品侧只接受严格更大的版本，其中之一会被静默丢弃。
            // 也不能只 INCR：计数器从 1 起涨时追不上商品侧已记着的历史版本，快照会被永久丢掉。
            Long seq = redis.execute(
                    new org.springframework.data.redis.core.script.DefaultRedisScript<>(LUA_NEXT_VERSION, Long.class),
                    java.util.List.of(key),
                    String.valueOf(maxReviewId));
            if (seq != null) {
                return seq;
            }
        } catch (RuntimeException e) {
            log.warn("[Review] 聚合版本计数器不可用，退回最大评价 id（删除评价后可能停在旧值）: spuId={} reason={}",
                    spuId, e.getMessage());
        }
        return maxReviewId;
    }

    public Snapshot read(Long spuId) {
        long[] buckets = new long[5];
        long total = 0;
        long sum = 0;
        long maxId = 0;
        List<Map<String, Object>> rows = reviewMapper.ratingDistribution(spuId);

        for (Map<String, Object> row : rows) {
            Integer rating = intOf(row, "rating");
            Long count = longOf(row, "cnt");
            // 版本取「这次聚合覆盖到的最大评价 id」，与星级无关 ——
            // 放在上面那个 if 里会让越界行（理论上不该有）悄悄压低版本，
            // 而版本偏低的表现是「新快照被当成旧的丢掉」，评分停在旧值上
            Long rowMaxId = longOf(row, "max_id");
            if (rowMaxId != null) {
                maxId = Math.max(maxId, rowMaxId);
            }
            // 星级越界的行直接跳过：distribution 只有五格，硬塞会下标越界。
            // rating 在库上没有 CHECK 约束（只有 DTO 校验），所以这不是理论情况
            if (rating == null || count == null || rating < 1 || rating > 5) {
                continue;
            }
            buckets[rating - 1] = count;
            total += count;
            sum += (long) rating * count;
        }

        List<Long> distribution = new ArrayList<>(5);
        for (long bucket : buckets) {
            distribution.add(bucket);
        }

        // 一位小数是**展示口径**，不是精度损失：商品标题旁边、评价区头部、
        // 前端商品卡的星星旁边都要显示同一个数。这里保留两位的话，回写到
        // product_spu 的值会比评价区多一位，两个数字在同一屏上直接打架
        BigDecimal average = total == 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(sum)
                        .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP)
                        .setScale(2, RoundingMode.HALF_UP);

        return new Snapshot(total, average, distribution, nextVersion(spuId, maxId));
    }

    /**
     * 按列名取值，忽略大小写。
     * <p>
     * H2 与 MySQL 对返回列名的大小写处理不同（{@code DATABASE_TO_LOWER} 只影响 H2，
     * 而 MySQL 原样返回），写死 {@code row.get("cnt")} 会在其中一种库上拿到 null，
     * 而 null 会被当成"这个星级没有评价"——统计悄悄少算，不报错。
     */
    private static Object column(Map<String, Object> row, String name) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Integer intOf(Map<String, Object> row, String name) {
        Object value = column(row, name);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long longOf(Map<String, Object> row, String name) {
        Object value = column(row, name);
        return value instanceof Number number ? number.longValue() : null;
    }
}
