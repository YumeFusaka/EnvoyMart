package yumefusaka.envoymart.reviewservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.reviewservice.entity.ReviewEntity;
import yumefusaka.envoymart.reviewservice.mapper.ReviewMapper;
import yumefusaka.envoymart.reviewservice.model.CreateReviewRequest;
import yumefusaka.envoymart.reviewservice.model.ReviewResponse;
import yumefusaka.envoymart.reviewservice.service.ReviewService;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class ReviewServiceImpl implements ReviewService {

    private final ReviewMapper reviewMapper;

    public ReviewServiceImpl(ReviewMapper reviewMapper) {
        this.reviewMapper = reviewMapper;
    }

    @Override
    @Transactional
    public ReviewResponse createReview(CreateReviewRequest request) {
        ReviewEntity entity = new ReviewEntity();
        entity.setProductId(request.getProductId());
        entity.setOrderId(request.getOrderId());
        entity.setUserId(request.getUserId());
        entity.setRating(request.getRating());
        entity.setContent(request.getContent());
        entity.setImages(request.getImages());
        entity.setCreatedAt(LocalDateTime.now());
        reviewMapper.insert(entity);
        log.info("评价已创建: productId={}, userId={}, rating={}", request.getProductId(), request.getUserId(), request.getRating());
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
