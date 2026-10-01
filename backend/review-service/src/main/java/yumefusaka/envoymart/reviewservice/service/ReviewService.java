package yumefusaka.envoymart.reviewservice.service;

import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.reviewservice.model.CreateReviewRequest;
import yumefusaka.envoymart.reviewservice.model.MyReviewItem;
import yumefusaka.envoymart.reviewservice.model.ReviewResponse;
import yumefusaka.envoymart.reviewservice.model.ReviewStatistics;

public interface ReviewService {

    ReviewResponse create(String userId, CreateReviewRequest request);

    /**
     * 按商品分页列评价。
     *
     * @param rating 只列该星级的评价；{@code null} 表示不筛
     */
    PageResult<ReviewResponse> listBySpu(Long spuId, Integer rating, boolean hasImage, int page, int size);

    /** 我发表过的评价（含被隐藏的，状态如实透出），带商品卡片数据 */
    PageResult<MyReviewItem> listMine(String userId, Long orderId, int page, int size);

    ReviewStatistics statistics(Long spuId);

    /** 标记「有用」。同一用户重复点会被去重 */
    void markUseful(String userId, Long reviewId);

    /**
     * 重算所有有评价商品的评分聚合并播出去，返回重算的商品数。
     * <p>
     * 正常路径不需要它：提交、隐藏、恢复都会自己发事件。它补的是<b>没走接口的写入</b>——
     * 种子数据、运维直接改库、以及消息丢失后的对账。这类"重算入口"在生产系统里
     * 是必需品：没有它，一旦派生数据错了，唯一的修法是手工改库。
     */
    int republishAllAggregates();
}
