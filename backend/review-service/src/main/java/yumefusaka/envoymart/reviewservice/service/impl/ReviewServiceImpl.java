package yumefusaka.envoymart.reviewservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.reviewservice.client.OrderClient;
import yumefusaka.envoymart.reviewservice.entity.ReviewEntity;
import yumefusaka.envoymart.reviewservice.entity.ReviewImageEntity;
import yumefusaka.envoymart.reviewservice.mapper.ReviewImageMapper;
import yumefusaka.envoymart.reviewservice.mapper.ReviewMapper;
import yumefusaka.envoymart.reviewservice.model.CreateReviewRequest;
import yumefusaka.envoymart.reviewservice.model.OrderSnapshot;
import yumefusaka.envoymart.reviewservice.model.ReviewResponse;
import yumefusaka.envoymart.reviewservice.model.ReviewStatistics;
import yumefusaka.envoymart.reviewservice.service.ReviewService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReviewServiceImpl implements ReviewService {

    private static final String STATUS_PUBLISHED = "PUBLISHED";
    /** 可以评价的订单状态：收到货之后才有资格 */
    private static final Set<String> REVIEWABLE_ORDER_STATUS = Set.of("RECEIVED", "COMPLETED");
    private static final int MAX_IMAGES = 9;

    private final ReviewMapper reviewMapper;
    private final ReviewImageMapper reviewImageMapper;
    private final OrderClient orderClient;

    public ReviewServiceImpl(ReviewMapper reviewMapper,
                             ReviewImageMapper reviewImageMapper,
                             OrderClient orderClient) {
        this.reviewMapper = reviewMapper;
        this.reviewImageMapper = reviewImageMapper;
        this.orderClient = orderClient;
    }

    @Override
    @Transactional
    public ReviewResponse create(String userId, CreateReviewRequest request) {
        // 评价必须来自一次真实且已完成的购买。此前这里完全不校验：
        // orderId 只标了 @NotNull，填什么都不管 —— 实测填一个不存在的订单号、
        // 填别人的订单号、给从没买过的商品打分，三种情况全部被接受
        OrderSnapshot.Item item = requirePurchased(userId, request.getOrderId(), request.getOrderItemId());

        List<String> images = normalizeImages(request.getImages());

        ReviewEntity entity = new ReviewEntity();
        // 商品信息**由订单行反查**，不采信请求体：否则调用方可以决定自己在评哪个商品
        entity.setSpuId(item.getSpuId());
        entity.setSkuId(item.getSkuId());
        entity.setOrderId(request.getOrderId());
        entity.setOrderItemId(item.getId());
        entity.setUserId(userId);
        entity.setRating(request.getRating());
        entity.setContent(request.getContent());
        entity.setIsAnonymous(Boolean.TRUE.equals(request.getAnonymous()) ? 1 : 0);
        entity.setStatus(STATUS_PUBLISHED);
        entity.setUsefulCount(0);
        entity.setCreatedAt(Times.now());

        try {
            reviewMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 表上有 (order_item_id, user_id) 唯一约束。先查后插在并发下拦不住 ——
            // 两个请求会同时查不到，然后都插入
            throw new IllegalStateException("该商品已经评价过了");
        }

        for (int i = 0; i < images.size(); i++) {
            ReviewImageEntity image = new ReviewImageEntity();
            image.setReviewId(entity.getId());
            image.setUrl(images.get(i));
            image.setSort(i);
            reviewImageMapper.insert(image);
        }

        log.info("评价已创建: spuId={}, orderItemId={}, rating={}, images={}",
                entity.getSpuId(), entity.getOrderItemId(), entity.getRating(), images.size());
        return toResponse(entity, images);
    }

    @Override
    public List<ReviewResponse> listBySpu(Long spuId) {
        List<ReviewEntity> reviews = reviewMapper.selectList(new LambdaQueryWrapper<ReviewEntity>()
                .eq(ReviewEntity::getSpuId, spuId)
                .eq(ReviewEntity::getStatus, STATUS_PUBLISHED)
                .orderByDesc(ReviewEntity::getCreatedAt));
        if (reviews.isEmpty()) {
            return List.of();
        }

        // 一次取完所有图片再按 reviewId 分组，避免逐条评价查一次
        Map<Long, List<String>> imagesByReview = reviewImageMapper.selectBySpuId(spuId).stream()
                .collect(Collectors.groupingBy(
                        ReviewImageEntity::getReviewId,
                        Collectors.mapping(ReviewImageEntity::getUrl, Collectors.toList())));

        return reviews.stream()
                .map(review -> toResponse(review, imagesByReview.getOrDefault(review.getId(), List.of())))
                .toList();
    }

    @Override
    public ReviewStatistics statistics(Long spuId) {
        List<ReviewEntity> reviews = reviewMapper.selectList(new LambdaQueryWrapper<ReviewEntity>()
                .eq(ReviewEntity::getSpuId, spuId)
                .eq(ReviewEntity::getStatus, STATUS_PUBLISHED));
        if (reviews.isEmpty()) {
            return ReviewStatistics.builder()
                    .spuId(spuId).total(0).average(0)
                    .distribution(List.of(0L, 0L, 0L, 0L, 0L)).withImage(0)
                    .build();
        }

        long[] buckets = new long[5];
        long sum = 0;
        for (ReviewEntity review : reviews) {
            int rating = review.getRating() == null ? 0 : review.getRating();
            if (rating >= 1 && rating <= 5) {
                buckets[rating - 1]++;
                sum += rating;
            }
        }

        List<Long> distribution = new ArrayList<>(5);
        for (long bucket : buckets) {
            distribution.add(bucket);
        }

        long withImage = reviewImageMapper.selectBySpuId(spuId).stream()
                .map(ReviewImageEntity::getReviewId)
                .distinct()
                .count();

        // 平均分保留一位小数：前端展示的是「4.8 分」，多余的精度没有意义
        double average = Math.round((double) sum / reviews.size() * 10) / 10.0;
        return ReviewStatistics.builder()
                .spuId(spuId)
                .total(reviews.size())
                .average(average)
                .distribution(distribution)
                .withImage(withImage)
                .build();
    }

    @Override
    @Transactional
    public void markUseful(String userId, Long reviewId) {
        ReviewEntity review = reviewId == null ? null : reviewMapper.selectById(reviewId);
        if (review == null || !STATUS_PUBLISHED.equals(review.getStatus())) {
            throw new IllegalArgumentException("评价不存在");
        }
        // 用条件更新自增，而不是「读出来 +1 再写回」：后者在并发点赞下会互相覆盖，
        // 十个赞最后只加上去两三个
        reviewMapper.update(null, new LambdaUpdateWrapper<ReviewEntity>()
                .eq(ReviewEntity::getId, reviewId)
                .setSql("useful_count = useful_count + 1"));
    }

    /**
     * 确认这笔订单是当前用户的、状态允许评价、且单里确实有这一行。
     * <p>
     * 注意判的是<b>业务码</b>而不是 HTTP 状态码：order-service 的异常被统一包成
     * HTTP 200 + {@code code=500}，而 Feign 只按状态码判断成败、不会抛异常。
     */
    private OrderSnapshot.Item requirePurchased(String userId, Long orderId, Long orderItemId) {
        Result<OrderSnapshot> result;
        try {
            result = orderClient.getOrder(userId, orderId);
        } catch (Exception e) {
            log.warn("[Review] 回查订单失败 orderId={}: {}", orderId, e.getMessage());
            throw new IllegalStateException("订单服务暂时不可用，请稍后再试");
        }
        if (result == null || result.getCode() == null || result.getCode() != 200 || result.getData() == null) {
            // 不区分「不存在」与「不属于你」，避免成为订单号存在性的探测接口
            throw new IllegalArgumentException("订单不存在");
        }

        OrderSnapshot order = result.getData();
        if (!REVIEWABLE_ORDER_STATUS.contains(order.getStatus())) {
            throw new IllegalStateException("订单尚未完成，收货后才能评价");
        }

        return order.getItems() == null ? null : order.getItems().stream()
                .filter(item -> orderItemId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("该订单中不含此商品，无法评价"));
    }

    private List<String> normalizeImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        return images.stream()
                .filter(url -> url != null && !url.isBlank())
                .limit(MAX_IMAGES)
                .toList();
    }

    private ReviewResponse toResponse(ReviewEntity entity, List<String> images) {
        boolean anonymous = entity.getIsAnonymous() != null && entity.getIsAnonymous() == 1;
        return ReviewResponse.builder()
                .id(entity.getId())
                .spuId(entity.getSpuId())
                .skuId(entity.getSkuId())
                .orderId(entity.getOrderId())
                // 匿名评价不返回 userId —— 脱敏在服务端做，不指望前端「不显示」
                .userId(anonymous ? null : entity.getUserId())
                .rating(entity.getRating())
                .content(entity.getContent())
                .images(images)
                .anonymous(anonymous)
                .status(entity.getStatus())
                .replyContent(entity.getReplyContent())
                .replyAt(entity.getReplyAt())
                .usefulCount(entity.getUsefulCount())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
