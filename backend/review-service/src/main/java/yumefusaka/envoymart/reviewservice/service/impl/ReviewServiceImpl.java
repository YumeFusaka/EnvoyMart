package yumefusaka.envoymart.reviewservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.reviewservice.client.OrderClient;
import yumefusaka.envoymart.reviewservice.entity.ReviewEntity;
import yumefusaka.envoymart.reviewservice.mapper.ReviewMapper;
import yumefusaka.envoymart.reviewservice.model.CreateReviewRequest;
import yumefusaka.envoymart.reviewservice.model.OrderSnapshot;
import yumefusaka.envoymart.reviewservice.model.ReviewResponse;
import yumefusaka.envoymart.reviewservice.service.ReviewService;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class ReviewServiceImpl implements ReviewService {

    private final ReviewMapper reviewMapper;
    private final OrderClient orderClient;

    public ReviewServiceImpl(ReviewMapper reviewMapper, OrderClient orderClient) {
        this.reviewMapper = reviewMapper;
        this.orderClient = orderClient;
    }

    @Override
    @Transactional
    public ReviewResponse createReview(String userId, CreateReviewRequest request) {
        // 评价必须来自一次真实购买。此前这里完全不校验：orderId 只标了 @NotNull，
        // 填什么都不管。实测三种情况全部被接受——orderId 填一个根本不存在的数字、
        // 填<b>别人的</b>订单号、以及给一件自己从没买过的商品打分。
        requirePurchased(userId, request.getOrderId(), request.getProductId());

        // 同一订单里的同一商品只能评一次，否则刷评价没有任何成本
        Long reviewed = reviewMapper.selectCount(new LambdaQueryWrapper<ReviewEntity>()
                .eq(ReviewEntity::getOrderId, request.getOrderId())
                .eq(ReviewEntity::getProductId, request.getProductId()));
        if (reviewed != null && reviewed > 0) {
            throw new IllegalStateException("该商品在这笔订单中已经评价过了");
        }

        ReviewEntity entity = new ReviewEntity();
        entity.setProductId(request.getProductId());
        entity.setOrderId(request.getOrderId());
        // 归属以网关注入的身份为准，不用请求体里的值——请求体是调用方可改的
        entity.setUserId(userId);
        entity.setRating(request.getRating());
        entity.setContent(request.getContent());
        entity.setImages(request.getImages());
        entity.setCreatedAt(LocalDateTime.now());
        reviewMapper.insert(entity);
        log.info("评价已创建: productId={}, userId={}, rating={}", request.getProductId(), userId, request.getRating());
        return toResponse(entity);
    }

    @Override
    public List<ReviewResponse> listReviews(Long productId) {
        return reviewMapper.selectList(new LambdaQueryWrapper<ReviewEntity>()
                        .eq(ReviewEntity::getProductId, productId)
                        .orderByDesc(ReviewEntity::getCreatedAt))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 确认这笔订单是当前用户的，且单里确实有这件商品。
     * <p>
     * 注意判的是<b>业务码</b>而不是 HTTP 状态码：order-service 的异常被统一包成
     * HTTP 200 + {@code code=500}，而 Feign 只按状态码判断成败、不会抛异常。
     * 这里 catch 兜的是连不上/超时一类的传输失败，业务失败靠下面的 code 判断。
     */
    private void requirePurchased(String userId, Long orderId, Long productId) {
        Result<OrderSnapshot> result;
        try {
            result = orderClient.getOrder(userId, orderId);
        } catch (Exception e) {
            log.warn("[Review] 回查订单失败 orderId={}: {}", orderId, e.getMessage());
            throw new IllegalStateException("订单服务暂时不可用，请稍后再试");
        }
        if (result == null || result.getCode() == null || result.getCode() != 200 || result.getData() == null) {
            // 不区分"不存在"与"不属于你"，避免成为订单号存在性的探测接口
            throw new IllegalArgumentException("订单不存在");
        }
        OrderSnapshot order = result.getData();
        boolean contains = order.getItems() != null && order.getItems().stream()
                .anyMatch(item -> productId.equals(item.getProductId()));
        if (!contains) {
            throw new IllegalArgumentException("该订单中不含此商品，无法评价");
        }
    }

    private ReviewResponse toResponse(ReviewEntity entity) {
        return ReviewResponse.builder()
                .id(entity.getId())
                .productId(entity.getProductId())
                .orderId(entity.getOrderId())
                .userId(entity.getUserId())
                .rating(entity.getRating())
                .content(entity.getContent())
                .images(entity.getImages())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
