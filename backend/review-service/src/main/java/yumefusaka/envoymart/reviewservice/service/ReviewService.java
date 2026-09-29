package yumefusaka.envoymart.reviewservice.service;

import yumefusaka.envoymart.reviewservice.model.CreateReviewRequest;
import yumefusaka.envoymart.reviewservice.model.ReviewResponse;
import yumefusaka.envoymart.reviewservice.model.ReviewStatistics;

import java.util.List;

public interface ReviewService {

    ReviewResponse create(String userId, CreateReviewRequest request);

    List<ReviewResponse> listBySpu(Long spuId);

    ReviewStatistics statistics(Long spuId);

    /** 标记「有用」。同一用户重复点会被去重 */
    void markUseful(String userId, Long reviewId);
}
