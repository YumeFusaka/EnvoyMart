package yumefusaka.envoymart.reviewservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestHeader;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import org.springframework.web.bind.annotation.*;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.reviewservice.model.CreateReviewRequest;
import yumefusaka.envoymart.reviewservice.model.ReviewResponse;
import yumefusaka.envoymart.reviewservice.service.ReviewService;

import java.util.List;

@RestController
@RequestMapping("/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    public Result<ReviewResponse> create(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @Valid @RequestBody CreateReviewRequest request) {
        return Result.success(reviewService.createReview(userId, request));
    }

    @GetMapping("/{productId}")
    public Result<List<ReviewResponse>> listByProduct(@PathVariable Long productId) {
        return Result.success(reviewService.listReviews(productId));
    }
}
