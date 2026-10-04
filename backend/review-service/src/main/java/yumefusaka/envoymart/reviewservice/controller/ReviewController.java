package yumefusaka.envoymart.reviewservice.controller;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.reviewservice.model.CreateReviewRequest;
import yumefusaka.envoymart.reviewservice.model.MyReviewItem;
import yumefusaka.envoymart.reviewservice.model.ReviewResponse;
import yumefusaka.envoymart.reviewservice.model.ReviewStatistics;
import yumefusaka.envoymart.reviewservice.service.ReviewService;

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
            @Valid @RequestBody CreateReviewRequest request,
            HttpServletRequest httpRequest) {
        return Result.success(reviewService.create(userId, request, clientIpOf(httpRequest)));
    }

    /**
     * 取调用方 IP，用于同商品短窗口刷评计数。
     * <p>
     * <b>这里是服务实例，不是网关</b>：{@code getRemoteAddr()} 拿到的是网关的地址 ——
     * 所有请求都变成同一个 IP，那道闸会退化成「全站共享一个计数器」，几个人同时评价
     * 就把彼此拦下。所以优先取网关透传的真实 IP（{@code X-Forwarded-For} 第一段 /
     * {@code X-Real-IP}）。
     * <p>
     * <b>这些头可被伪造</b>，因此它只用作「限流的维度」，不用作任何授权判据：
     * 伪造 IP 的代价是攻击者把自己和别人的计数搅在一起，而拦不拦得住的最后一道闸
     * （已收货订单 + 每日条数）不受影响。
     * <p>
     * 直连服务端口的本地调用（无网关头）退化为 {@code getRemoteAddr()}，
     * 那是开发环境的常态，不影响正确性。
     */
    private static String clientIpOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 取第一段：X-Forwarded-For 是「客户端, 代理1, 代理2」的顺序
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 按商品列评价，可分页、可按星级筛。
     * <p>
     * <b>不读身份头</b>：任何登录用户拿到的都是同一份数据，没有按人过滤的分支。
     * <p>
     * 路径用 {@code /spu/{spuId}} 而不是 {@code /{spuId}}：后者与 {@code POST /reviews}
     * 同级，读起来分不清哪个参数是什么；而 {@code /mine} 是另一个字面量，与它不冲突
     * （Spring 的字面量段优先于模板段）。
     */
    @GetMapping("/spu/{spuId}")
    public Result<PageResult<ReviewResponse>> listBySpu(
            @PathVariable("spuId") Long spuId,
            @RequestParam(value = "rating", required = false) Integer rating,
            @RequestParam(value = "hasImage", defaultValue = "false") boolean hasImage,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return Result.success(reviewService.listBySpu(spuId, rating, hasImage, page, size));
    }

    @GetMapping("/spu/{spuId}/statistics")
    public Result<ReviewStatistics> statistics(@PathVariable("spuId") Long spuId) {
        return Result.success(reviewService.statistics(spuId));
    }

    /**
     * 我发表过的评价，含被隐藏的。
     * <p>
     * 放在 {@code /spu/{spuId}} 之前声明只是一种阅读顺序上的暗示——Spring 匹配看的是
     * 路径本身而不是声明顺序，{@code /reviews/mine} 永远命中这里，不会被
     * {@code /spu/{spuId}} 抢走（段数都不同）。
     */
    @GetMapping("/mine")
    public Result<PageResult<MyReviewItem>> listMine(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return Result.success(reviewService.listMine(userId, orderId, page, size));
    }

    @PostMapping("/{id}/useful")
    public Result<Void> markUseful(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        reviewService.markUseful(userId, id);
        return Result.success(null);
    }

    /**
     * 重算所有有评价商品的评分聚合并播给 product-service。
     * <p>
     * 补的是不走接口的写入：种子数据、运维直接改库、以及消息丢失后的对账。
     * 是幂等的（重算而非累加），随时可以再跑一次。
     */
    @PostMapping("/internal/aggregates/republish")
    public Result<Integer> republishAggregates() {
        return Result.success(reviewService.republishAllAggregates());
    }
}
