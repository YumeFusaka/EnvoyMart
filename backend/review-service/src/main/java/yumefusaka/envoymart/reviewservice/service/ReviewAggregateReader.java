package yumefusaka.envoymart.reviewservice.service;

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
@Component
public class ReviewAggregateReader {

    private final ReviewMapper reviewMapper;

    public ReviewAggregateReader(ReviewMapper reviewMapper) {
        this.reviewMapper = reviewMapper;
    }

    /**
     * @param total        已发布评价条数
     * @param average      平均分，一位小数（展示口径），第二位补零；没有评价时为 {@code 0.00}
     * @param distribution 各星级条数，下标 0 是 1 星
     */
    public record Snapshot(long total, BigDecimal average, List<Long> distribution) {
    }

    public Snapshot read(Long spuId) {
        long[] buckets = new long[5];
        long total = 0;
        long sum = 0;

        for (Map<String, Object> row : reviewMapper.ratingDistribution(spuId)) {
            Integer rating = intOf(row, "rating");
            Long count = longOf(row, "cnt");
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

        return new Snapshot(total, average, distribution);
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
