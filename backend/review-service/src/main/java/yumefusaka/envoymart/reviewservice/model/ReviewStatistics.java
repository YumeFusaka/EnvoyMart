package yumefusaka.envoymart.reviewservice.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 商品评分统计。列表页的星级、详情页的评分分布都用它 */
@Data
@Builder
public class ReviewStatistics {

    private Long spuId;
    private long total;
    private double average;
    /** 各星级数量，下标 0 对应 1 星、下标 4 对应 5 星 */
    private List<Long> distribution;
    /** 有图评价数，前端据此显示「有图 (12)」这类筛选 */
    private long withImage;
}
