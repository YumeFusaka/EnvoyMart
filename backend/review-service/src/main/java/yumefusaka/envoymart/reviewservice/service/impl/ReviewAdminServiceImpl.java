package yumefusaka.envoymart.reviewservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.reviewservice.entity.ReviewEntity;
import yumefusaka.envoymart.reviewservice.entity.ReviewImageEntity;
import yumefusaka.envoymart.reviewservice.mapper.ReviewImageMapper;
import yumefusaka.envoymart.reviewservice.mapper.ReviewMapper;
import yumefusaka.envoymart.reviewservice.model.admin.AdminReviewDetail;
import yumefusaka.envoymart.reviewservice.model.admin.AdminReviewQuery;
import yumefusaka.envoymart.reviewservice.model.admin.AdminReviewSummary;
import yumefusaka.envoymart.reviewservice.mq.ReviewAggregatePublisher;
import yumefusaka.envoymart.reviewservice.service.ReviewAdminService;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReviewAdminServiceImpl implements ReviewAdminService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String STATUS_HIDDEN = "HIDDEN";
    private static final Set<String> VALID_STATUSES = Set.of(STATUS_PENDING, STATUS_PUBLISHED, STATUS_HIDDEN);

    private final ReviewMapper reviewMapper;
    private final ReviewImageMapper reviewImageMapper;
    private final ReviewAggregatePublisher aggregatePublisher;

    public ReviewAdminServiceImpl(ReviewMapper reviewMapper,
                                  ReviewImageMapper reviewImageMapper,
                                  ReviewAggregatePublisher aggregatePublisher) {
        this.reviewMapper = reviewMapper;
        this.reviewImageMapper = reviewImageMapper;
        this.aggregatePublisher = aggregatePublisher;
    }

    @Override
    public PageResult<AdminReviewSummary> list(AdminReviewQuery query) {
        Page<ReviewEntity> page = new Page<>(query.mpCurrent(), query.safeSize());
        Page<ReviewEntity> result = reviewMapper.selectPage(page, adminWrapper(query));

        // 图片数一次查完这一页的：逐条评价查一次就是 20 次额外查询，
        // 而这只是列表上的一个角标
        Map<Long, Integer> imageCounts = imageCountsOf(result.getRecords());

        List<AdminReviewSummary> records = result.getRecords().stream()
                .map(entity -> toSummary(entity, imageCounts.getOrDefault(entity.getId(), 0)))
                .toList();

        return PageResult.<AdminReviewSummary>builder()
                .records(records)
                .total(result.getTotal())
                .page(query.zeroBasedPage())
                .size(query.safeSize())
                .build();
    }

    @Override
    public AdminReviewDetail detail(Long reviewId) {
        ReviewEntity review = requireReview(reviewId);
        List<String> images = reviewImageMapper.selectList(new LambdaQueryWrapper<ReviewImageEntity>()
                        .eq(ReviewImageEntity::getReviewId, reviewId)
                        .orderByAsc(ReviewImageEntity::getSort))
                .stream()
                .map(ReviewImageEntity::getUrl)
                .toList();

        return AdminReviewDetail.builder()
                .review(toSummary(review, images.size()))
                .images(images)
                .build();
    }

    @Override
    @Transactional
    public AdminReviewSummary changeStatus(Long reviewId, String status, String reason, String operatorId) {
        ReviewEntity review = requireReview(reviewId);

        String target = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!VALID_STATUSES.contains(target)) {
            throw new IllegalArgumentException("不支持的评价状态：" + status);
        }
        // 先校验再判幂等：否则「已经是 HIDDEN 的评价」上重复提交一次不带原因的隐藏请求
        // 会被放行，而这个请求单独看是不合法的
        String trimmedReason = reason == null ? "" : reason.trim();
        if (STATUS_HIDDEN.equals(target) && trimmedReason.isEmpty()) {
            throw new IllegalArgumentException("隐藏评价必须填写原因");
        }

        if (target.equals(review.getStatus())) {
            // 重复点击是常态，不该产生副作用：刷新「谁在什么时候因为什么隐藏的」
            // 会让这条记录最终指向最后一个手滑的人，而不是真正做决定的那次
            log.info("评价状态未变化，跳过: reviewId={}, status={}, operator={}", reviewId, target, operatorId);
            return toSummary(review, imagesOf(reviewId).size());
        }

        LambdaUpdateWrapper<ReviewEntity> update = new LambdaUpdateWrapper<ReviewEntity>()
                .eq(ReviewEntity::getId, reviewId)
                .set(ReviewEntity::getStatus, target);
        if (STATUS_HIDDEN.equals(target)) {
            update.set(ReviewEntity::getHiddenReason, trimmedReason)
                    .set(ReviewEntity::getHiddenBy, operatorId)
                    .set(ReviewEntity::getHiddenAt, Times.now());
        } else {
            // 恢复时清空而不是留着：HIDDEN 与「有隐藏原因」必须是同一件事的两个说法。
            // 留着一条已恢复评价的隐藏原因，会让「按原因排查」查出已经被撤销的操作
            update.set(ReviewEntity::getHiddenReason, null)
                    .set(ReviewEntity::getHiddenBy, null)
                    .set(ReviewEntity::getHiddenAt, null);
        }
        reviewMapper.update(null, update);

        log.info("评价状态变更: reviewId={}, {} -> {}, operator={}, reason={}",
                reviewId, review.getStatus(), target, operatorId,
                STATUS_HIDDEN.equals(target) ? trimmedReason : null);

        // 隐藏一条评价会让商品的均分与评论数一起变——商品侧那份冗余必须跟着动。
        // 只有牵涉 PUBLISHED 的变更才要发：PENDING 与 HIDDEN 都不在聚合里，
        // 它们之间互转对商品评分没有任何影响
        if (STATUS_PUBLISHED.equals(review.getStatus()) || STATUS_PUBLISHED.equals(target)) {
            aggregatePublisher.publishAfterCommit(review.getSpuId());
        }

        ReviewEntity updated = reviewMapper.selectById(reviewId);
        return toSummary(updated, imagesOf(reviewId).size());
    }

    @Override
    @Transactional
    public AdminReviewSummary reply(Long reviewId, String content, String operatorId) {
        ReviewEntity review = requireReview(reviewId);

        if (STATUS_HIDDEN.equals(review.getStatus())) {
            // 拒绝而不是照写：公开侧只查 PUBLISHED，写给已隐藏评价的回复
            // 不会有任何买家看到。留下一条永远看不见的回复，只会让运营
            // 以为「我回过了」，而问题没有解决
            throw new IllegalStateException("该评价已隐藏，公开侧不可见，无法回复");
        }

        String text = content == null ? "" : content.trim();
        LambdaUpdateWrapper<ReviewEntity> update = new LambdaUpdateWrapper<ReviewEntity>()
                .eq(ReviewEntity::getId, reviewId);
        if (text.isEmpty()) {
            update.set(ReviewEntity::getReplyContent, null)
                    .set(ReviewEntity::getReplyBy, null)
                    .set(ReviewEntity::getReplyAt, null);
        } else {
            update.set(ReviewEntity::getReplyContent, text)
                    .set(ReviewEntity::getReplyBy, operatorId)
                    .set(ReviewEntity::getReplyAt, Times.now());
        }
        reviewMapper.update(null, update);

        log.info("评价回复{}: reviewId={}, operator={}", text.isEmpty() ? "撤回" : "写入", reviewId, operatorId);

        ReviewEntity updated = reviewMapper.selectById(reviewId);
        return toSummary(updated, imagesOf(reviewId).size());
    }

    private LambdaQueryWrapper<ReviewEntity> adminWrapper(AdminReviewQuery query) {
        LambdaQueryWrapper<ReviewEntity> wrapper = new LambdaQueryWrapper<>();

        if (query.getSpuId() != null) {
            wrapper.eq(ReviewEntity::getSpuId, query.getSpuId());
        }
        if (query.getSkuId() != null) {
            wrapper.eq(ReviewEntity::getSkuId, query.getSkuId());
        }
        if (query.getOrderId() != null) {
            wrapper.eq(ReviewEntity::getOrderId, query.getOrderId());
        }
        if (query.getUserId() != null && !query.getUserId().isBlank()) {
            wrapper.eq(ReviewEntity::getUserId, query.getUserId().trim());
        }
        if (query.getRating() != null) {
            // 取值非法时抛 400 —— 静默忽略会让页面展示「全部评分」而看的人以为筛过了
            if (query.getRating() < 1 || query.getRating() > 5) {
                throw new IllegalArgumentException("评分只能是 1-5：" + query.getRating());
            }
            wrapper.eq(ReviewEntity::getRating, query.getRating());
        }

        List<String> statuses = query.statusList();
        if (!statuses.isEmpty()) {
            for (String status : statuses) {
                if (!VALID_STATUSES.contains(status.toUpperCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("不支持的评价状态：" + status);
                }
            }
            wrapper.in(ReviewEntity::getStatus, statuses);
        }

        if (query.getHasReply() != null) {
            // 「撤回回复」写的是 null（不是空串），所以 isNull 就是「未回复」的准确判据
            if (query.getHasReply()) {
                wrapper.isNotNull(ReviewEntity::getReplyContent);
            } else {
                wrapper.isNull(ReviewEntity::getReplyContent);
            }
        }

        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            // 只搜评价内容。不像售后那样再 or 上单号与用户 id：
            // 那两个维度这里都有各自的精确筛选参数，模糊匹配只会让结果更难解释
            wrapper.like(ReviewEntity::getContent, query.getKeyword().trim());
        }

        if (query.getCreatedFrom() != null) {
            wrapper.ge(ReviewEntity::getCreatedAt, query.getCreatedFrom());
        }
        if (query.getCreatedTo() != null) {
            wrapper.le(ReviewEntity::getCreatedAt, query.getCreatedTo());
        }

        // 排序带唯一兜底列：只按 created_at 排时，同一秒提交的两条评价翻页会漏一条、重一条
        return wrapper.orderByDesc(ReviewEntity::getCreatedAt).orderByDesc(ReviewEntity::getId);
    }

    private Map<Long, Integer> imageCountsOf(List<ReviewEntity> reviews) {
        if (reviews.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = reviews.stream().map(ReviewEntity::getId).toList();
        return reviewImageMapper.selectList(new LambdaQueryWrapper<ReviewImageEntity>()
                        .in(ReviewImageEntity::getReviewId, ids))
                .stream()
                .collect(Collectors.groupingBy(ReviewImageEntity::getReviewId,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
    }

    private List<String> imagesOf(Long reviewId) {
        return reviewImageMapper.selectList(new LambdaQueryWrapper<ReviewImageEntity>()
                        .eq(ReviewImageEntity::getReviewId, reviewId)
                        .orderByAsc(ReviewImageEntity::getSort))
                .stream()
                .map(ReviewImageEntity::getUrl)
                .toList();
    }

    /** 写成方法引用会在泛型推断上打架（selectById 的入参是 Serializable），就直白点 */
    private ReviewEntity requireReview(Long reviewId) {
        ReviewEntity review = reviewId == null ? null : reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new IllegalArgumentException("评价不存在");
        }
        return review;
    }

    private AdminReviewSummary toSummary(ReviewEntity entity, int imageCount) {
        return AdminReviewSummary.builder()
                .id(entity.getId())
                .spuId(entity.getSpuId())
                .skuId(entity.getSkuId())
                .orderId(entity.getOrderId())
                .orderItemId(entity.getOrderItemId())
                // 不抹 userId：这条脱敏是给公开侧看的，管理端要能回答「这条匿名差评是谁写的」
                .userId(entity.getUserId())
                .anonymous(entity.getIsAnonymous() != null && entity.getIsAnonymous() == 1)
                .rating(entity.getRating())
                .content(entity.getContent())
                .imageCount(imageCount)
                .status(entity.getStatus())
                .replyContent(entity.getReplyContent())
                .replyAt(entity.getReplyAt())
                .replyBy(entity.getReplyBy())
                .hiddenReason(entity.getHiddenReason())
                .hiddenBy(entity.getHiddenBy())
                .hiddenAt(entity.getHiddenAt())
                .usefulCount(entity.getUsefulCount())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
