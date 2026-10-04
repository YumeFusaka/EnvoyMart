package yumefusaka.envoymart.reviewservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.contract.OrderItemResponse;
import yumefusaka.envoymart.contract.OrderResponse;
import yumefusaka.envoymart.contract.ProductSummary;
import yumefusaka.envoymart.reviewservice.client.OrderClient;
import yumefusaka.envoymart.reviewservice.client.ProductClient;
import yumefusaka.envoymart.reviewservice.entity.ReviewEntity;
import yumefusaka.envoymart.reviewservice.entity.ReviewImageEntity;
import yumefusaka.envoymart.reviewservice.entity.ReviewUsefulEntity;
import yumefusaka.envoymart.reviewservice.mapper.ReviewImageMapper;
import yumefusaka.envoymart.reviewservice.mapper.ReviewMapper;
import yumefusaka.envoymart.reviewservice.mapper.ReviewUsefulMapper;
import yumefusaka.envoymart.reviewservice.model.CreateReviewRequest;
import yumefusaka.envoymart.reviewservice.model.MyReviewItem;
import yumefusaka.envoymart.reviewservice.model.ReviewResponse;
import yumefusaka.envoymart.reviewservice.model.ReviewStatistics;
import yumefusaka.envoymart.reviewservice.mq.ReviewAggregatePublisher;
import yumefusaka.envoymart.reviewservice.service.ReviewAggregateReader;
import yumefusaka.envoymart.reviewservice.service.ReviewFloodGuard;
import yumefusaka.envoymart.reviewservice.service.ReviewNewAccountGuard;
import yumefusaka.envoymart.reviewservice.service.ReviewService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReviewServiceImpl implements ReviewService {

    private static final String STATUS_PUBLISHED = "PUBLISHED";
    /** 新账号保护期内的评价先进这个状态，由管理端复用已有的隐藏/审核能力处理 */
    private static final String STATUS_PENDING = "PENDING";
    /** 可以评价的订单状态：收到货之后才有资格 */
    private static final Set<String> REVIEWABLE_ORDER_STATUS = Set.of("RECEIVED", "COMPLETED");
    private static final int MAX_IMAGES = 9;

    /**
     * 同一用户每天最多发表多少条评价。
     * <p>
     * 5 这个数不是拍的：一个真实用户一天能收到货并写下评价的订单不会超过个位数，
     * 而刷评的要的是量。卡在正常用量的上沿，误伤的概率最低、拦下的最多。
     * <p>
     * <b>它挡不住什么</b>：注册一批小号轮流刷。那需要设备指纹或实名维度，
     * 超出这个项目的数据面——如实记在待改进清单里，比假装防线完整要好。
     */
    private static final int MAX_DAILY_REVIEWS = 5;

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;

    private final ReviewMapper reviewMapper;
    private final ReviewImageMapper reviewImageMapper;
    private final ReviewUsefulMapper reviewUsefulMapper;
    private final OrderClient orderClient;
    private final ProductClient productClient;
    private final ReviewAggregateReader aggregateReader;
    private final ReviewAggregatePublisher aggregatePublisher;
    private final ReviewFloodGuard floodGuard;
    private final ReviewNewAccountGuard newAccountGuard;

    public ReviewServiceImpl(ReviewMapper reviewMapper,
                             ReviewImageMapper reviewImageMapper,
                             ReviewUsefulMapper reviewUsefulMapper,
                             OrderClient orderClient,
                             ProductClient productClient,
                             ReviewAggregateReader aggregateReader,
                             ReviewAggregatePublisher aggregatePublisher,
                             ReviewFloodGuard floodGuard,
                             ReviewNewAccountGuard newAccountGuard) {
        this.reviewMapper = reviewMapper;
        this.reviewImageMapper = reviewImageMapper;
        this.reviewUsefulMapper = reviewUsefulMapper;
        this.orderClient = orderClient;
        this.productClient = productClient;
        this.aggregateReader = aggregateReader;
        this.aggregatePublisher = aggregatePublisher;
        this.floodGuard = floodGuard;
        this.newAccountGuard = newAccountGuard;
    }

    @Override
    @Transactional
    public ReviewResponse create(String userId, CreateReviewRequest request, String clientIp) {
        // 防刷放在回查订单**之前**：它只查本地两张表，而回查要跨服务一次往返。
        // 拦不住的请求不该先花掉一次下游调用
        requireNotFlooding(userId, request.getContent());

        // 同 (商品, IP) 的短窗口计数。放在回查订单之前是同一个理由：它只碰 Redis。
        // 这一步需要 spuId，而 spuId 要从订单行反查 —— 所以真正判的位置在下面拿到 item 之后，
        // 这里先不做（见 requireNotSameIpFlooding）

        // 评价必须来自一次真实且已完成的购买。此前这里完全不校验：
        // orderId 只标了 @NotNull，填什么都不管 —— 实测填一个不存在的订单号、
        // 填别人的订单号、给从没买过的商品打分，三种情况全部被接受
        PurchasedItem purchased = requirePurchased(userId, request.getOrderId(), request.getOrderItemId());
        OrderItemResponse item = purchased.item();

        requireNotSameIpFlooding(item.getSpuId(), clientIp, userId);

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
        // 新账号（首次收货不满 7 天）的评价先进待审。判定放行的代价只是一次比对，
        // 但它依赖一次跨服务查询 —— 而这次查询与 requirePurchased 走的是同一个下游，
        // 所以放在已经确认订单确实存在、且属于本人之后
        entity.setStatus(decideStatus(userId, purchased.receivedAt()));
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

        // 事务提交后再播：提交前播，消费者可能读到还没提交的聚合，把旧值写进商品表
        aggregatePublisher.publishAfterCommit(entity.getSpuId());

        return toResponse(entity, images);
    }

    @Override
    public PageResult<ReviewResponse> listBySpu(Long spuId, Integer rating, boolean hasImage, int page, int size) {
        int safePage = safePage(page);
        int safeSize = safeSize(size);

        LambdaQueryWrapper<ReviewEntity> wrapper = new LambdaQueryWrapper<ReviewEntity>()
                .eq(ReviewEntity::getSpuId, spuId)
                .eq(ReviewEntity::getStatus, STATUS_PUBLISHED);
        if (rating != null) {
            // 越界直接拒绝而不是当作"不筛"：`?rating=9` 回一份完整列表，
            // 调用方会以为筛选生效了、只是这个星级恰好有这么多条
            if (rating < 1 || rating > 5) {
                throw new IllegalArgumentException("星级只能是 1 到 5");
            }
            wrapper.eq(ReviewEntity::getRating, rating);
        }
        if (hasImage) {
            // 「有图」在服务端筛，而不是拉一页回来再过滤：后者在带图评价稀疏时
            // 会给出一个看起来像坏了的空列表（这一页恰好都没图），而总数又显示着有几十条。
            // 子查询没有用户输入，直接写死
            wrapper.exists("select 1 from review_image i where i.review_id = review.id");
        }
        // 排序带唯一兜底列：只按 created_at 排时，同一秒提交的两条评价翻页会漏一条、重一条
        wrapper.orderByDesc(ReviewEntity::getCreatedAt).orderByDesc(ReviewEntity::getId);

        Page<ReviewEntity> result = reviewMapper.selectPage(new Page<>(safePage + 1L, safeSize), wrapper);
        List<ReviewEntity> records = result.getRecords();
        if (records.isEmpty()) {
            return PageResult.<ReviewResponse>builder()
                    .records(List.of()).total(result.getTotal()).page(safePage).size(safeSize).build();
        }

        // 只取当前页的图片：拉全商品的图再丢掉大半，是分页之前的分页
        Map<Long, List<String>> imagesByReview = imagesOf(records);

        List<ReviewResponse> items = records.stream()
                .map(review -> toResponse(review, imagesByReview.getOrDefault(review.getId(), List.of())))
                .toList();

        return PageResult.<ReviewResponse>builder()
                .records(items).total(result.getTotal()).page(safePage).size(safeSize).build();
    }

    @Override
    public PageResult<MyReviewItem> listMine(String userId, Long orderId, int page, int size) {
        int safePage = safePage(page);
        int safeSize = safeSize(size);

        LambdaQueryWrapper<ReviewEntity> wrapper = new LambdaQueryWrapper<ReviewEntity>()
                .eq(ReviewEntity::getUserId, userId);
        if (orderId != null) {
            // 订单详情页只要这一单的：整页拉回来再在浏览器里过滤，
            // 在评价多的账号上会「明明评过却显示没评」——那一页里恰好没有这一单
            wrapper.eq(ReviewEntity::getOrderId, orderId);
        }
        // 不带 status 条件：被隐藏的评价也要让用户看见，只是标注出来
        Page<ReviewEntity> result = reviewMapper.selectPage(new Page<>(safePage + 1L, safeSize),
                wrapper.orderByDesc(ReviewEntity::getCreatedAt).orderByDesc(ReviewEntity::getId));

        List<ReviewEntity> records = result.getRecords();
        if (records.isEmpty()) {
            return PageResult.<MyReviewItem>builder()
                    .records(List.of()).total(result.getTotal()).page(safePage).size(safeSize).build();
        }

        Map<Long, List<String>> imagesByReview = imagesOf(records);
        Map<Long, ProductSummary> products = productsOf(records);

        List<MyReviewItem> items = records.stream()
                .map(review -> toMyItem(review,
                        imagesByReview.getOrDefault(review.getId(), List.of()),
                        products.get(review.getSpuId())))
                .toList();

        return PageResult.<MyReviewItem>builder()
                .records(items).total(result.getTotal()).page(safePage).size(safeSize).build();
    }

    @Override
    public ReviewStatistics statistics(Long spuId) {
        ReviewAggregateReader.Snapshot snapshot = aggregateReader.read(spuId);
        Integer withImage = reviewImageMapper.countWithImage(spuId);
        return ReviewStatistics.builder()
                .spuId(spuId)
                .total(snapshot.total())
                .average(snapshot.average().doubleValue())
                .distribution(snapshot.distribution())
                .withImage(withImage == null ? 0 : withImage)
                .build();
    }

    /**
     * 标记「有用」。
     * <p>
     * <b>先记票、再加计数，两步在同一个事务里。</b>只加计数不记票的话，
     * 这个数字就成了一个谁都能反复顶的计数器——而它是所有用户都看得见的。
     * 顺序不能反：先加计数再记票，撞上唯一约束回滚时计数已经加过了，得靠事务一起兜回来，
     * 而先记票的话那次插入本身就是判据。
     */
    @Override
    @Transactional
    public void markUseful(String userId, Long reviewId) {
        ReviewEntity review = reviewId == null ? null : reviewMapper.selectById(reviewId);
        if (review == null || !STATUS_PUBLISHED.equals(review.getStatus())) {
            throw new IllegalArgumentException("评价不存在");
        }

        ReviewUsefulEntity vote = new ReviewUsefulEntity();
        vote.setReviewId(reviewId);
        vote.setUserId(userId);
        vote.setCreatedAt(Times.now());
        try {
            reviewUsefulMapper.insert(vote);
        } catch (DuplicateKeyException e) {
            // 409 而不是静默成功：静默成功会让前端把计数加一，而服务端并没有加，
            // 界面上的数字从此和服务端对不上，且没人知道是从哪一次开始错的
            throw new IllegalStateException("你已经标记过这条评价了");
        }

        // 用条件更新自增，而不是「读出来 +1 再写回」：后者在并发点赞下会互相覆盖，
        // 十个赞最后只加上去两三个
        reviewMapper.update(null, new LambdaUpdateWrapper<ReviewEntity>()
                .eq(ReviewEntity::getId, reviewId)
                .setSql("useful_count = useful_count + 1"));
    }

    @Override
    public int republishAllAggregates() {
        List<Long> spuIds = reviewMapper.selectPublishedSpuIds();
        for (Long spuId : spuIds) {
            // 逐条发、不带事务：一条失败不该让其余商品继续停在错值上
            aggregatePublisher.publishAfterCommit(spuId);
        }
        log.info("[Review] 评分聚合重建已触发: 商品数={}", spuIds.size());
        return spuIds.size();
    }

    // ==================== 校验 ====================

    /**
     * 防刷的两道闸：每日条数上限 + 当日重复内容。
     * <p>
     * <b>为什么用数据库计数而不是 Redis 计数器</b>：评价表本身就是这份数据的权威来源，
     * 按 (user_id, created_at) 数一遍就是答案。用一个会过期、会因故障清零的副本去表达
     * 「今天发了几条」，等于给防刷开了一个"缓存一挂就失效"的后门——而这个项目里
     * 评价表规模远没到需要为它建计数器的地步。
     * <p>
     * <b>重复内容的口径</b>：同一用户当天内容完全相同的评价。跨商品才算数——同一个商品
     * 他本来就只能评一次（订单行唯一约束）。这条挡的是把同一段好评复制到多个商品下。
     * 只打星不写字不算重复，空内容之间没有可复制的东西。
     */
    private void requireNotFlooding(String userId, String content) {
        LocalDateTime todayStart = Times.now().toLocalDate().atStartOfDay();

        // 计数不带 status：被隐藏的评价不该把额度还回来，否则刷完一批被隐藏就能接着刷
        long today = reviewMapper.selectCount(new LambdaQueryWrapper<ReviewEntity>()
                .eq(ReviewEntity::getUserId, userId)
                .ge(ReviewEntity::getCreatedAt, todayStart));
        if (today >= MAX_DAILY_REVIEWS) {
            throw new IllegalStateException(
                    "今天已发表 " + today + " 条评价，每天最多 " + MAX_DAILY_REVIEWS + " 条，请明天再来");
        }

        if (content == null || content.isBlank()) {
            return;
        }
        long duplicated = reviewMapper.selectCount(new LambdaQueryWrapper<ReviewEntity>()
                .eq(ReviewEntity::getUserId, userId)
                .eq(ReviewEntity::getContent, content)
                .ge(ReviewEntity::getCreatedAt, todayStart));
        if (duplicated > 0) {
            throw new IllegalStateException("你今天已经发表过内容完全相同的评价了");
        }
    }

    /**
     * 确认这笔订单是当前用户的、状态允许评价、且单里确实有这一行。
     * <p>
     * 注意判的是<b>业务码</b>而不是 HTTP 状态码：order-service 的异常被统一包成
     * HTTP 200 + {@code code=500}，而 Feign 只按状态码判断成败、不会抛异常。
     */
    private PurchasedItem requirePurchased(String userId, Long orderId, Long orderItemId) {
        Result<OrderResponse> result;
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

        OrderResponse order = result.getData();
        if (!REVIEWABLE_ORDER_STATUS.contains(order.getStatus())) {
            throw new IllegalStateException("订单尚未完成，收货后才能评价");
        }

        OrderItemResponse item = order.getItems() == null ? null : order.getItems().stream()
                .filter(orderItem -> orderItemId.equals(orderItem.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("该订单中不含此商品，无法评价"));
        // 连同订单的收货时间一起带出来：新账号判据要用它作对照，
        // 而它就在刚取回的这份响应里 —— 再查一次订单只是同一份数据的第二次往返
        return new PurchasedItem(item, order.getReceivedAt());
    }

    /**
     * 评价的前置事实：命中的订单行 + 这笔订单的收货时间。
     * <p>
     * 合在一起返回而不是让调用方再查一次：两份数据必须来自<b>同一次</b>订单读取，
     * 否则「校验过的那一单」与「用来判收货时间的那一单」可能是两笔不同的订单。
     */
    private record PurchasedItem(OrderItemResponse item, LocalDateTime receivedAt) {
    }

    /**
     * 同 (商品, IP) 的短窗口刷评计数。
     * <p>
     * <b>超限时拒绝而不是转待审</b>：这里拦的是「同一台机器短时间内在同一个商品下灌评价」，
     * 那是机器行为，没有「先收下再判」的价值 —— 收下来只会把待审队列灌满。
     * 而新账号保护期不同，它可能是一次真实的首购，所以那边是转待审。
     */
    private void requireNotSameIpFlooding(Long spuId, String clientIp, String userId) {
        var count = floodGuard.recordAndCheck(spuId, clientIp);
        if (count.isEmpty()) {
            return;
        }
        if (count.getAsInt() > ReviewFloodGuard.MAX_SAME_SPU_PER_IP) {
            log.warn("[Review] 同商品同 IP 短窗口评价超限，已拒绝: spuId={} ip={} user={} count={}",
                    spuId, clientIp, userId, count.getAsInt());
            throw new IllegalStateException("操作过于频繁，请稍后再试");
        }
    }

    /**
     * 决定这条评价的初始状态。
     * <p>
     * 正常账号直接 {@code PUBLISHED}（老行为不变）；被保护的账号写 {@code PENDING}。
     * 判不了时按 {@code PUBLISHED} 放行 —— 见 {@link ReviewNewAccountGuard}。
     */
    private String decideStatus(String userId, LocalDateTime orderReceivedAt) {
        return newAccountGuard.shouldHoldForReview(userId, orderReceivedAt)
                ? STATUS_PENDING : STATUS_PUBLISHED;
    }

    // ==================== 装配 ====================

    /** 当前页的图片，按 reviewId 分组。顺序按 sort，与用户上传时看到的一致 */
    private Map<Long, List<String>> imagesOf(List<ReviewEntity> reviews) {
        List<Long> ids = reviews.stream().map(ReviewEntity::getId).toList();
        return reviewImageMapper.selectList(new LambdaQueryWrapper<ReviewImageEntity>()
                        .in(ReviewImageEntity::getReviewId, ids)
                        .orderByAsc(ReviewImageEntity::getSort))
                .stream()
                .collect(Collectors.groupingBy(ReviewImageEntity::getReviewId,
                        Collectors.mapping(ReviewImageEntity::getUrl, Collectors.toList())));
    }

    /**
     * 一次性把这一页涉及的商品查回来（去重后）。
     * <p>
     * <b>失败整批降级为空，不让异常冒出去</b>：用户打开「我的评价」是为了看自己写了什么，
     * 商品缩略图是锦上添花。product-service 抖动时正确的表现是"卡片少一块"，
     * 而不是整页 500。
     */
    private Map<Long, ProductSummary> productsOf(List<ReviewEntity> reviews) {
        List<Long> spuIds = reviews.stream().map(ReviewEntity::getSpuId).distinct().toList();
        try {
            Result<List<ProductSummary>> result = productClient.summaries(
                    spuIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
            // 判业务码而不是 HTTP 码：服务间应答一律 200 + 体里的 code
            if (result == null || result.getCode() == null || result.getCode() != 200 || result.getData() == null) {
                log.warn("[Review] 批量取商品未成功，我的评价将不显示商品卡片: spuIds={}", spuIds);
                return Map.of();
            }
            return result.getData().stream()
                    .collect(Collectors.toMap(ProductSummary::getId, Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            log.warn("[Review] 批量取商品异常，我的评价将不显示商品卡片: spuIds={}", spuIds, e);
            return Map.of();
        }
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
                // 匿名评价不返回昵称；实名评价只返回脱敏昵称。
                // 原样返回 userId 等于把登录名挂在公开评价区上——任何登录用户都能
                // 拿到别人的账号名，而评价列表本来就是所有人可见的
                .nickname(anonymous ? null : nicknameOf(entity.getUserId()))
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

    private MyReviewItem toMyItem(ReviewEntity entity, List<String> images, ProductSummary product) {
        return MyReviewItem.builder()
                .id(entity.getId())
                .spuId(entity.getSpuId())
                .skuId(entity.getSkuId())
                .orderId(entity.getOrderId())
                .orderItemId(entity.getOrderItemId())
                .rating(entity.getRating())
                .content(entity.getContent())
                .images(images)
                .anonymous(entity.getIsAnonymous() != null && entity.getIsAnonymous() == 1)
                .status(entity.getStatus())
                .hiddenReason(entity.getHiddenReason())
                .replyContent(entity.getReplyContent())
                .replyAt(entity.getReplyAt())
                .usefulCount(entity.getUsefulCount())
                .createdAt(entity.getCreatedAt())
                .product(product)
                .build();
    }

    /**
     * 评价列表上的展示名。
     * <p>
     * 由 userId 脱敏而来，而不是去查真实昵称：这个项目里 userId 就是登录名，
     * 公开面直接打出去等于给了一个可以遍历他人身份的标识。首尾字符保留，
     * 是为了让同一个人在自己的多条评价里看起来一致。
     */
    private static String nicknameOf(String userId) {
        if (userId == null || userId.isBlank()) {
            return "用户";
        }
        if (userId.length() == 1) {
            return userId + "***";
        }
        return userId.charAt(0) + "***" + userId.charAt(userId.length() - 1);
    }

    private static int safePage(int page) {
        return Math.max(page, 0);
    }

    private static int safeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
