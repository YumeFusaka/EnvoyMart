package yumefusaka.envoymart.reviewservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.reviewservice.model.CreateReviewRequest;
import yumefusaka.envoymart.reviewservice.model.ReviewResponse;
import yumefusaka.envoymart.reviewservice.model.ReviewStatistics;
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
        return Result.success(reviewService.create(userId, request));
    }

    /**
     * 按商品列评价。<b>不读身份头</b>：任何登录用户拿到的都是同一份数据，没有按人过滤的分支。
     * <p>
     * 但这不等于匿名可访问——网关白名单没有收录 {@code /reviews/**}，游客请求在此之外就被
     * 挡成 401 了。这与前端一致：商城整体在登录之后（路由没有 {@code meta.public}），
     * 当前不存在游客逛商品详情的场景。将来若要开放游客浏览，路由与网关白名单要一起放开。
     * <p>
     * 路径用 {@code /spu/{spuId}} 而不是 {@code /{spuId}}：后者与 {@code POST /reviews}
     * 同级，读起来分不清哪个参数是什么。
     */
    @GetMapping("/spu/{spuId}")
    public Result<List<ReviewResponse>> listBySpu(@PathVariable("spuId") Long spuId) {
        return Result.success(reviewService.listBySpu(spuId));
    }

    @GetMapping("/spu/{spuId}/statistics")
    public Result<ReviewStatistics> statistics(@PathVariable("spuId") Long spuId) {
        return Result.success(reviewService.statistics(spuId));
    }

    @PostMapping("/{id}/useful")
    public Result<Void> markUseful(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        reviewService.markUseful(userId, id);
        return Result.success(null);
    }
}
