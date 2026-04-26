package yumefusaka.envoymart.reviewservice.service;

import yumefusaka.envoymart.reviewservice.model.CreateReviewRequest;
import yumefusaka.envoymart.reviewservice.model.ReviewResponse;

import java.util.List;

public interface ReviewService {
    ReviewResponse createReview(CreateReviewRequest request);
    List<ReviewResponse> listReviews(Long productId);
}
