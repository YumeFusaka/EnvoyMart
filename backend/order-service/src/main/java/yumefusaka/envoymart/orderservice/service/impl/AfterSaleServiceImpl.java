package yumefusaka.envoymart.orderservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.contract.StockChangeRequest;
import yumefusaka.envoymart.orderservice.client.PaymentClient;
import yumefusaka.envoymart.orderservice.client.ProductClient;
import yumefusaka.envoymart.orderservice.entity.AfterSaleEntity;
import yumefusaka.envoymart.orderservice.entity.AfterSaleLogEntity;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;
import yumefusaka.envoymart.orderservice.entity.OrderItemEntity;
import yumefusaka.envoymart.orderservice.entity.OrderStatusLogEntity;
import yumefusaka.envoymart.orderservice.mapper.AfterSaleLogMapper;
import yumefusaka.envoymart.orderservice.mapper.AfterSaleMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderItemMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderStatusLogMapper;
import yumefusaka.envoymart.contract.AfterSalePreview;
import yumefusaka.envoymart.orderservice.model.AfterSaleDetail;
import yumefusaka.envoymart.orderservice.model.AfterSaleResponse;
import yumefusaka.envoymart.orderservice.model.AfterSaleStatus;
import yumefusaka.envoymart.orderservice.model.AfterSaleType;
import yumefusaka.envoymart.orderservice.model.ApplyAfterSaleRequest;
import yumefusaka.envoymart.orderservice.model.OrderStatus;
import yumefusaka.envoymart.orderservice.model.PolicyDecision;
import yumefusaka.envoymart.orderservice.model.admin.AdminAfterSaleDetail;
import yumefusaka.envoymart.orderservice.model.admin.AdminAfterSaleQuery;
import yumefusaka.envoymart.orderservice.model.admin.StatusLogView;
import yumefusaka.envoymart.contract.RefundRequest;
import yumefusaka.envoymart.contract.RefundResponse;
import yumefusaka.envoymart.orderservice.service.AfterSalePolicyEngine;
import yumefusaka.envoymart.orderservice.service.AfterSaleService;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AfterSaleServiceImpl implements AfterSaleService {

    private static final String OPERATOR_USER = "USER";
    private static final String OPERATOR_ADMIN = "ADMIN";

    /** 退货入库回补库存时写进库存流水的来源分类 */
    private static final String BIZ_TYPE_AFTER_SALE = "AFTER_SALE_RETURN";

    private final AfterSaleMapper afterSaleMapper;
    private final AfterSaleLogMapper afterSaleLogMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStatusLogMapper orderStatusLogMapper;
    private final AfterSalePolicyEngine policyEngine;
    private final PaymentClient paymentClient;
    private final ProductClient productClient;

    public AfterSaleServiceImpl(AfterSaleMapper afterSaleMapper,
                                AfterSaleLogMapper afterSaleLogMapper,
                                OrderMapper orderMapper,
                                OrderItemMapper orderItemMapper,
                                OrderStatusLogMapper orderStatusLogMapper,
                                AfterSalePolicyEngine policyEngine,
                                PaymentClient paymentClient,
                                ProductClient productClient) {
        this.afterSaleMapper = afterSaleMapper;
        this.afterSaleLogMapper = afterSaleLogMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.orderStatusLogMapper = orderStatusLogMapper;
        this.policyEngine = policyEngine;
        this.paymentClient = paymentClient;
        this.productClient = productClient;
    }

    @Override
    public AfterSalePreview preview(String userId, Long orderItemId, String type, boolean qualityIssue) {
        OrderItemEntity item = requireOwnedItem(userId, orderItemId);
        OrderEntity order = requireOrder(item.getOrderId());

        if (!AfterSaleType.isValid(type)) {
            throw new IllegalArgumentException("不支持的售后类型：" + type);
        }

        PolicyDecision decision = policyEngine.evaluate(order, item, type, qualityIssue);
        return AfterSalePreview.builder()
                .orderItemId(orderItemId)
                .type(type)
                .eligible(decision.isAllowed())
                .reason(decision.getReason())
                .maxRefundAmount(decision.getMaxRefundAmount())
                .itemSubtotal(item.getSubtotal())
                .docRef(decision.getDocRef())
                .requirements(decision.getRequirements())
                .build();
    }

    @Override
    @Transactional
    public AfterSaleResponse apply(String userId, ApplyAfterSaleRequest request) {
        if (!AfterSaleType.isValid(request.getType())) {
            throw new IllegalArgumentException("不支持的售后类型：" + request.getType());
        }

        OrderItemEntity item = requireOwnedItem(userId, request.getOrderItemId());
        OrderEntity order = requireOrder(item.getOrderId());

        // 同一订单行不能同时有两个进行中的售后单。
        // 应用层的先查后插拦不住并发，所以下面还有一道数据库唯一约束兜底 ——
        // 但那个约束是「已完成的不许再申请」，两条一起才完整
        Long active = afterSaleMapper.selectCount(new LambdaQueryWrapper<AfterSaleEntity>()
                .eq(AfterSaleEntity::getOrderItemId, item.getId())
                .in(AfterSaleEntity::getStatus, AfterSaleStatus.ACTIVE_NAMES));
        if (active != null && active > 0) {
            throw new IllegalStateException("该商品已有进行中的售后申请");
        }

        PolicyDecision decision = policyEngine.evaluate(order, item, request.getType(),
                Boolean.TRUE.equals(request.getQualityIssue()));
        if (!decision.isAllowed()) {
            // 政策不允许 —— 把原因原样带出去，用户需要知道具体卡在哪一条
            throw new IllegalStateException(decision.getReason());
        }

        List<String> images = request.getImages() == null ? List.of()
                : request.getImages().stream().filter(url -> url != null && !url.isBlank()).limit(9).toList();

        AfterSaleEntity entity = new AfterSaleEntity();
        entity.setAfterSaleNo(generateNo());
        entity.setOrderId(order.getId());
        entity.setOrderNo(order.getOrderNo());
        entity.setOrderItemId(item.getId());
        entity.setUserId(userId);
        entity.setType(request.getType());
        entity.setStatus(AfterSaleStatus.APPLIED.name());
        entity.setReason(request.getReason());
        entity.setDescription(request.getDescription());
        entity.setImages(images.isEmpty() ? null : String.join(",", images));
        // 申请金额取政策允许的上限，而不是订单行全额 —— 拆封的食品只能退部分
        entity.setRefundAmount(decision.getMaxRefundAmount() == null ? 0L : decision.getMaxRefundAmount());
        entity.setAppliedAt(Times.now());
        afterSaleMapper.insert(entity);

        writeLog(entity.getId(), null, AfterSaleStatus.APPLIED, OPERATOR_USER, userId,
                "用户申请" + AfterSaleType.text(request.getType()));

        log.info("售后申请已创建: no={}, orderNo={}, type={}, amount={}",
                entity.getAfterSaleNo(), order.getOrderNo(), entity.getType(), entity.getRefundAmount());
        return toResponse(entity, item, decision.getDocRef(), decision.getMaxRefundAmount());
    }

    @Override
    @Transactional
    public AfterSaleResponse audit(Long afterSaleId, boolean approved, String remark, String operatorId) {
        AfterSaleEntity entity = requireAfterSale(afterSaleId);

        AfterSaleStatus current = AfterSaleStatus.parse(entity.getStatus());
        if (current != AfterSaleStatus.APPLIED) {
            throw new IllegalStateException("售后单当前状态为「" + current.text() + "」，不可审核");
        }
        if (!approved && (remark == null || remark.isBlank())) {
            // 驳回必须给理由：用户看不到原因就只能反复申诉
            throw new IllegalArgumentException("驳回时必须填写理由");
        }

        AfterSaleStatus target = approved ? AfterSaleStatus.APPROVED : AfterSaleStatus.REJECTED;
        transit(entity, current, target, OPERATOR_ADMIN, operatorId,
                approved ? "审核通过" : remark);

        if (!approved) {
            return toResponse(entity, null, null, null);
        }

        // 仅退款不需要寄回，审核通过后直接进入退款
        if (!AfterSaleType.requiresReturn(entity.getType())) {
            refund(entity);
        }
        return toResponse(entity, null, null, null);
    }

    @Override
    @Transactional
    public AfterSaleResponse cancel(String userId, Long afterSaleId) {
        AfterSaleEntity entity = requireOwned(userId, afterSaleId);
        AfterSaleStatus current = AfterSaleStatus.parse(entity.getStatus());

        // 退款中不可撤：钱已经在路上，撤了钱也会到账，状态和事实对不上。
        // 已收货（RECEIVED）同样不可撤：货已经回到商家手里，撤销等于货被白拿
        if (current == AfterSaleStatus.REFUNDING || current == AfterSaleStatus.RECEIVED
                || current.isTerminal()) {
            throw new IllegalStateException("售后单当前状态为「" + current.text() + "」，不可撤销");
        }

        transit(entity, current, AfterSaleStatus.CANCELLED, OPERATOR_USER, userId, "用户撤销申请");
        return toResponse(entity, null, null, null);
    }

    @Override
    @Transactional
    public AfterSaleResponse shipBack(String userId, Long afterSaleId, String carrier, String trackingNo) {
        AfterSaleEntity entity = requireOwned(userId, afterSaleId);

        AfterSaleStatus current = AfterSaleStatus.parse(entity.getStatus());
        if (current != AfterSaleStatus.APPROVED) {
            throw new IllegalStateException("售后单当前状态为「" + current.text() + "」，不可填写寄回信息");
        }
        // 仅退款的单没有货要寄 —— 走到这里说明是流程被绕了，如实拒绝
        if (!AfterSaleType.requiresReturn(entity.getType())) {
            throw new IllegalStateException("该售后类型无需寄回商品");
        }

        LocalDateTime returnedAt = Times.now();
        transit(entity, current, AfterSaleStatus.RETURNING, OPERATOR_USER, userId,
                "用户已寄回：" + carrier + " " + trackingNo,
                wrapper -> wrapper
                        .set(AfterSaleEntity::getReturnCarrier, carrier)
                        .set(AfterSaleEntity::getReturnTrackingNo, trackingNo)
                        .set(AfterSaleEntity::getReturnedAt, returnedAt));

        // 条件更新只落库、不回写实体（transit 只同步了状态）。响应要展示刚填的值，
        // 就得在这里同步 —— 否则用户刚提交成功，看到的三个字段却都是空
        entity.setReturnCarrier(carrier);
        entity.setReturnTrackingNo(trackingNo);
        entity.setReturnedAt(returnedAt);

        log.info("售后单已寄回 no={} carrier={} trackingNo={}", entity.getAfterSaleNo(), carrier, trackingNo);
        return toResponse(entity, null, null, null);
    }

    @Override
    @Transactional
    public AfterSaleResponse confirmReceived(Long afterSaleId, String operatorId) {
        AfterSaleEntity entity = requireAfterSale(afterSaleId);

        AfterSaleStatus current = AfterSaleStatus.parse(entity.getStatus());
        if (current != AfterSaleStatus.RETURNING) {
            throw new IllegalStateException("售后单当前状态为「" + current.text() + "」，不可确认收货");
        }
        transit(entity, current, AfterSaleStatus.RECEIVED, OPERATOR_ADMIN, operatorId,
                "商家已收到退货：" + (entity.getReturnCarrier() == null ? "" : entity.getReturnCarrier() + " ")
                        + (entity.getReturnTrackingNo() == null ? "" : entity.getReturnTrackingNo()));
        // 货回到仓库，库存跟着还回来。**在退款之前**：入库是已发生的事实，
        // 而退款只是打款动作 —— 打款失败不该让库存也挂着不还
        restoreReturnedStockQuietly(entity);
        refund(entity);
        return toResponse(entity, null, null, null);
    }

    @Override
    @Transactional
    public AfterSaleResponse retryRefund(Long afterSaleId, String operatorId) {
        AfterSaleEntity entity = requireAfterSale(afterSaleId);

        if (AfterSaleStatus.parse(entity.getStatus()) != AfterSaleStatus.REFUNDING) {
            throw new IllegalStateException("只有「退款中」的售后单可以重试退款");
        }
        // 重试本身不改状态，但**要留一条流水**：否则「这单被重试过三次」这个事实
        // 只存在于日志里，而排障的人看的是流水
        writeLog(entity.getId(), AfterSaleStatus.REFUNDING, AfterSaleStatus.REFUNDING,
                OPERATOR_ADMIN, operatorId, "人工重试退款");
        refundFromRefunding(entity);
        return toResponse(entity, null, null, null);
    }

    @Override
    public PageResult<AfterSaleResponse> adminPage(AdminAfterSaleQuery query) {
        Page<AfterSaleEntity> page = new Page<>(query.mpCurrent(), query.safeSize());
        Page<AfterSaleEntity> result = afterSaleMapper.selectPage(page, adminWrapper(query));

        // 商品快照**一次查完这一页的**：toResponse 里那句 selectById 是给单条详情用的，
        // 放在列表里就是 20 次额外查询
        Map<Long, OrderItemEntity> items = itemsOf(result.getRecords());

        List<AfterSaleResponse> records = result.getRecords().stream()
                .map(entity -> toResponse(entity, items.get(entity.getOrderItemId()), null, null))
                .toList();

        return PageResult.<AfterSaleResponse>builder()
                .records(records)
                .total(result.getTotal())
                .page(query.zeroBasedPage())
                .size(query.safeSize())
                .build();
    }

    @Override
    public AdminAfterSaleDetail adminDetail(Long afterSaleId) {
        AfterSaleEntity entity = requireAfterSale(afterSaleId);

        return AdminAfterSaleDetail.builder()
                .afterSale(toResponse(entity, null, null, null))
                .logs(logsOf(afterSaleId, false))
                .build();
    }

    private LambdaQueryWrapper<AfterSaleEntity> adminWrapper(AdminAfterSaleQuery query) {
        LambdaQueryWrapper<AfterSaleEntity> wrapper = new LambdaQueryWrapper<>();

        if (query.getUserId() != null && !query.getUserId().isBlank()) {
            wrapper.eq(AfterSaleEntity::getUserId, query.getUserId().trim());
        }
        List<String> statuses = query.statusList();
        if (!statuses.isEmpty()) {
            // 逐个走 parse：取值非法时抛 IllegalArgumentException → 400，与同在这里的
            // type 校验、以及订单列表的 status 校验同一条理由 —— 静默忽略会让页面
            // 一本正经地展示「全部售后」，而看的人以为筛过了
            wrapper.in(AfterSaleEntity::getStatus,
                    statuses.stream().map(status -> AfterSaleStatus.parse(status).name()).toList());
        }
        if (query.getType() != null && !query.getType().isBlank()) {
            String type = query.getType().trim();
            // 类型取值非法时抛 400 —— 与状态同一条理由：静默忽略会让页面
            // 展示「全部售后」而看的人以为筛过了
            if (!AfterSaleType.isValid(type)) {
                throw new IllegalArgumentException("不支持的售后类型：" + type);
            }
            wrapper.eq(AfterSaleEntity::getType, type);
        }
        if (query.getAppliedFrom() != null) {
            wrapper.ge(AfterSaleEntity::getAppliedAt, query.getAppliedFrom());
        }
        if (query.getAppliedTo() != null) {
            wrapper.le(AfterSaleEntity::getAppliedAt, query.getAppliedTo());
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            String keyword = query.getKeyword().trim();
            // 三个条件的 **或** 必须整体包进 and(...)：or 的优先级低于 and，
            // 散着写会把上面的状态、类型、时间条件一起短路掉
            wrapper.and(w -> w.like(AfterSaleEntity::getAfterSaleNo, keyword)
                    .or().like(AfterSaleEntity::getOrderNo, keyword)
                    .or().like(AfterSaleEntity::getUserId, keyword));
        }

        // 排序带唯一兜底列：只按 applied_at 排时，同一秒申请的两单翻页会漏一条、重一条
        return wrapper.orderByDesc(AfterSaleEntity::getAppliedAt).orderByDesc(AfterSaleEntity::getId);
    }

    private Map<Long, OrderItemEntity> itemsOf(List<AfterSaleEntity> afterSales) {
        if (afterSales.isEmpty()) {
            return Map.of();
        }
        List<Long> itemIds = afterSales.stream()
                .map(AfterSaleEntity::getOrderItemId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItemEntity>()
                        .in(OrderItemEntity::getId, itemIds))
                .stream()
                .collect(Collectors.toMap(OrderItemEntity::getId, Function.identity(),
                        (first, second) -> first));
    }

    /** 售后单不存在时的统一入口 —— 详情、审核、收货、重试四条路都要这一句 */
    private AfterSaleEntity requireAfterSale(Long afterSaleId) {
        AfterSaleEntity entity = afterSaleId == null ? null : afterSaleMapper.selectById(afterSaleId);
        if (entity == null) {
            throw new IllegalArgumentException("售后单不存在");
        }
        return entity;
    }

    @Override
    public List<AfterSaleResponse> listByUser(String userId) {
        return afterSaleMapper.selectList(new LambdaQueryWrapper<AfterSaleEntity>()
                        .eq(AfterSaleEntity::getUserId, userId)
                        .orderByDesc(AfterSaleEntity::getId))
                .stream()
                .map(entity -> toResponse(entity, null, null, null))
                .toList();
    }

    @Override
    public AfterSaleDetail detail(String userId, Long afterSaleId) {
        AfterSaleEntity entity = requireOwned(userId, afterSaleId);
        return AfterSaleDetail.builder()
                .afterSale(toResponse(entity, null, null, null))
                .logs(logsOf(afterSaleId, true))
                .build();
    }

    /**
     * 执行退款并把售后单推到完成。
     * <p>
     * 退款失败**不回滚售后单状态**：状态推进与资金动作是两件事，
     * 把状态退回去会让「已经审核通过」这个事实消失，而用户看到的是一次莫名其妙的失败。
     * 失败时留在 REFUNDING 并留 ERROR —— 那是一个人能接手的状态。
     */
    private void refund(AfterSaleEntity entity) {
        AfterSaleStatus current = AfterSaleStatus.parse(entity.getStatus());
        transit(entity, current, AfterSaleStatus.REFUNDING, "SYSTEM", null, "发起退款");

        long amount = entity.getRefundAmount() == null ? 0L : entity.getRefundAmount();
        if (amount <= 0) {
            // 0 元售后（换货等）不调支付：支付侧拒绝 0 元退款是对的（「退款金额必须大于 0」），
            // 硬调只会让单子永远卡在退款中。
            // 也不投影订单：一分钱没动，把订单标成「退款中」再弹回去，用户的时间线上
            // 会白白多出一条「售后退款发起」——换货根本没有退款这回事
            transit(entity, AfterSaleStatus.REFUNDING, AfterSaleStatus.FINISHED,
                    "SYSTEM", null, "无需退款（金额为 0），直接完成");
            return;
        }

        // 订单投影为退款中：用户看订单列表时，「退款在路上」这件事必须立刻可见，
        // 而不是等退款完成才一次性变化
        projectOrderRefunding(entity);
        refundFromRefunding(entity);
    }

    /**
     * 从「退款中」推进到「已完成」。
     * <p>
     * <b>只把「支付调用」这一段兜住</b>：它失败时留在退款中并留 ERROR —— 那是一个人
     * 能接手的状态，配合 {@link #retryRefund} 可以重试；抛出去反而会让整个事务回滚，
     * 「已审核通过」这个事实也跟着消失。
     * <p>
     * {@code transit} 的失败则必须传出去。钱这时候已经退出去了，状态推不动是并发冲突
     * （另一个请求先把它推到了已完成），把它一起吞掉会返回一个「退款失败」的响应，
     * 而实际上退成功了 —— 那是最容易让人重复操作的一类假象。
     */
    private void refundFromRefunding(AfterSaleEntity entity) {
        String refundNo;
        try {
            Result<RefundResponse> result = paymentClient.refundForOrder(RefundRequest.builder()
                    .orderId(entity.getOrderId())
                    .afterSaleId(entity.getId())
                    .amount(entity.getRefundAmount())
                    .reason("售后退款")
                    .build());
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                throw new IllegalStateException(result == null ? "无响应" : result.getMsg());
            }
            refundNo = result.getData() == null ? "" : result.getData().getRefundNo();
        } catch (Exception e) {
            log.error("[AfterSale] 退款失败，售后单停留在退款中，可调用 retry-refund 重试 no={} orderNo={} amount={}: {}",
                    entity.getAfterSaleNo(), entity.getOrderNo(), entity.getRefundAmount(), e.getMessage());
            return;
        }
        transit(entity, AfterSaleStatus.REFUNDING, AfterSaleStatus.FINISHED,
                "SYSTEM", null, "退款完成：" + refundNo);
        projectOrderAfterFinish(entity);
        log.info("售后退款完成: no={}, amount={}, refundNo={}",
                entity.getAfterSaleNo(), entity.getRefundAmount(), refundNo);
    }

    /**
     * 把订单投影为「退款中」。
     * <p>
     * <b>失败只留痕、不抛出</b>：订单状态是所有订单行事实的投影，而这里是投影没跟上，
     * 抛出去会把已经发生的资金动作一起回滚掉（本地事务回滚撤销不了远端的退款调用）。
     * 投影丢了有 WARN，钱乱了就找不回来了 —— 两者的严重性不在一个量级。
     */
    private void projectOrderRefunding(AfterSaleEntity entity) {
        OrderEntity order = orderMapper.selectById(entity.getOrderId());
        if (order == null) {
            return;
        }
        OrderStatus current = OrderStatus.parse(order.getStatus());
        if (!current.canTransitTo(OrderStatus.REFUNDING)) {
            // 已经是退款中/已退款：重试或并发路径，属正常
            return;
        }
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                .eq(OrderEntity::getId, order.getId())
                .eq(OrderEntity::getStatus, current.name())
                .set(OrderEntity::getStatus, OrderStatus.REFUNDING.name()));
        if (updated > 0) {
            writeOrderLog(order.getId(), current, OrderStatus.REFUNDING,
                    "售后退款发起（" + entity.getAfterSaleNo() + "）");
        } else {
            log.warn("[AfterSale] 订单状态并发变更，未投影为退款中 orderId={} no={}",
                    order.getId(), entity.getAfterSaleNo());
        }
    }

    /**
     * 售后单完成后推进订单：所有订单行都退完 → 已退款；只退了一部分 → 回到已收货。
     * <p>
     * 回到 RECEIVED 而不是停在 REFUNDING：一单两件只退一件时，剩下的那件还在用户手上、
     * 售后权利也还在，订单却挂着「退款中」—— 展示与事实不符。回到「已收货」后，
     * 剩余商品可以继续申请售后，超时任务也会照常把它推进到已完成。
     */
    private void projectOrderAfterFinish(AfterSaleEntity entity) {
        OrderEntity order = orderMapper.selectById(entity.getOrderId());
        if (order == null) {
            return;
        }
        OrderStatus current = OrderStatus.parse(order.getStatus());
        if (current != OrderStatus.REFUNDING) {
            return;
        }

        List<OrderItemEntity> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItemEntity>().eq(OrderItemEntity::getOrderId, order.getId()));
        OrderStatus target = OrderStatus.RECEIVED;
        long finishedRefunded = 0L;
        if (!items.isEmpty()) {
            List<Long> itemIds = items.stream().map(OrderItemEntity::getId).toList();
            // 只数「真正退过钱」的完成售后：换货是「换」不是「退」，它完成时金额为 0，
            // 把它数进来会把一张没退过一分钱的订单标成「已退款」。
            // distinct 而不是 selectCount：一行理论上只可能有一条 FINISHED 售后
            // （数据库唯一约束保证），但统计与判定按「行」来算，语义更直
            finishedRefunded = afterSaleMapper.selectList(new LambdaQueryWrapper<AfterSaleEntity>()
                            .in(AfterSaleEntity::getOrderItemId, itemIds)
                            .eq(AfterSaleEntity::getStatus, AfterSaleStatus.FINISHED.name())
                            .gt(AfterSaleEntity::getRefundAmount, 0L))
                    .stream()
                    .map(AfterSaleEntity::getOrderItemId)
                    .distinct()
                    .count();
            if (finishedRefunded >= itemIds.size()) {
                target = OrderStatus.REFUNDED;
            }
        }

        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                .eq(OrderEntity::getId, order.getId())
                .eq(OrderEntity::getStatus, OrderStatus.REFUNDING.name())
                .set(OrderEntity::getStatus, target.name()));
        if (updated > 0) {
            // 文案分三态：换货这类 0 元售后完成时写「部分商品退款完成」是假话——
            // 一分钱没退，用户却在流水里看到「退款完成」四个字
            String remark = target == OrderStatus.REFUNDED ? "全部商品退款完成"
                    : (finishedRefunded == 0 ? "售后完成（未退款），订单继续" : "部分商品退款完成，订单继续");
            writeOrderLog(order.getId(), OrderStatus.REFUNDING, target, remark);
        }
    }

    private void writeOrderLog(Long orderId, OrderStatus from, OrderStatus to, String remark) {
        OrderStatusLogEntity entry = new OrderStatusLogEntity();
        entry.setOrderId(orderId);
        entry.setFromStatus(from.name());
        entry.setToStatus(to.name());
        entry.setOperatorType("SYSTEM");
        entry.setRemark(remark);
        entry.setCreatedAt(Times.now());
        orderStatusLogMapper.insert(entry);
    }

    /**
     * 退货入库：把退回的商品数量补回库存。
     * <p>
     * <b>失败不抛出</b>：货实际已经在仓库里，库存数字没跟上而已 —— 抛出去会把
     * 「商家已确认收货」这件事一起回滚，而货是真实存在的。留 ERROR 等人工对账，
     * 与订单侧的 {@code restoreStockQuietly} 同一套哲学。
     * <p>
     * 仅退款类型没有货回来，不调用。
     */
    private void restoreReturnedStockQuietly(AfterSaleEntity entity) {
        if (!AfterSaleType.requiresReturn(entity.getType())) {
            return;
        }
        OrderItemEntity item = entity.getOrderItemId() == null
                ? null : orderItemMapper.selectById(entity.getOrderItemId());
        if (item == null) {
            log.error("[AfterSale] 退货入库找不到订单行，库存未回补 no={} orderItemId={}",
                    entity.getAfterSaleNo(), entity.getOrderItemId());
            return;
        }
        try {
            Result<?> result = productClient.restoreStock(StockChangeRequest.builder()
                    .skuId(item.getSkuId())
                    .quantity(item.getQuantity())
                    .bizType(BIZ_TYPE_AFTER_SALE)
                    .bizId(entity.getAfterSaleNo())
                    .remark("售后退货入库")
                    .build());
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                throw new IllegalStateException(result == null ? "无响应" : result.getMsg());
            }
            log.info("[AfterSale] 退货入库回补库存 no={} skuId={} quantity={}",
                    entity.getAfterSaleNo(), item.getSkuId(), item.getQuantity());
        } catch (Exception e) {
            log.error("[AfterSale] 退货入库回补库存失败，需人工对账 no={} skuId={} quantity={}: {}",
                    entity.getAfterSaleNo(), item.getSkuId(), item.getQuantity(), e.getMessage());
        }
    }

    private void transit(AfterSaleEntity entity, AfterSaleStatus from, AfterSaleStatus to,
                         String operatorType, String operatorId, String remark) {
        transit(entity, from, to, operatorType, operatorId, remark, null);
    }

    /**
     * 状态推进 + 流水，可选携带额外字段。
     * <p>
     * 额外字段走 {@code extraSet} 而不是每次单独 update：状态与随状态一起写入的字段
     * （寄回单号等）必须落在**同一条条件更新**里 —— 分两次写就会出现「状态已是退货中、
     * 单号还没写进去」的中间态，而这个中间态恰好是排障时最先看到的。
     */
    private void transit(AfterSaleEntity entity, AfterSaleStatus from, AfterSaleStatus to,
                         String operatorType, String operatorId, String remark,
                         Consumer<LambdaUpdateWrapper<AfterSaleEntity>> extraSet) {
        LambdaUpdateWrapper<AfterSaleEntity> wrapper = new LambdaUpdateWrapper<AfterSaleEntity>()
                .eq(AfterSaleEntity::getId, entity.getId())
                .eq(AfterSaleEntity::getStatus, from.name())
                .set(AfterSaleEntity::getStatus, to.name())
                .set(to == AfterSaleStatus.FINISHED, AfterSaleEntity::getFinishedAt, Times.now())
                .set(to == AfterSaleStatus.APPROVED || to == AfterSaleStatus.REJECTED,
                        AfterSaleEntity::getAuditedAt, Times.now())
                .set(to == AfterSaleStatus.REJECTED, AfterSaleEntity::getAuditRemark, remark);
        if (extraSet != null) {
            extraSet.accept(wrapper);
        }
        int updated = afterSaleMapper.update(null, wrapper);
        if (updated == 0) {
            // 状态被并发改过。条件更新是唯一能裁决它的地方
            throw new IllegalStateException("售后单状态刚刚发生变化，请刷新后重试");
        }
        entity.setStatus(to.name());
        writeLog(entity.getId(), from, to, operatorType, operatorId, remark);
    }

    /**
     * 售后流水。
     *
     * @param maskOperator 用户侧调用时置 true：操作人 id 对用户抹掉。
     *                     管理端账号 id 透给用户既无意义，也平白多一个可枚举的内部标识
     */
    private List<StatusLogView> logsOf(Long afterSaleId, boolean maskOperator) {
        return afterSaleLogMapper.selectList(
                        new LambdaQueryWrapper<AfterSaleLogEntity>()
                                .eq(AfterSaleLogEntity::getAfterSaleId, afterSaleId)
                                .orderByAsc(AfterSaleLogEntity::getId))
                .stream()
                .map(entry -> StatusLogView.builder()
                        .fromStatus(entry.getFromStatus())
                        .toStatus(entry.getToStatus())
                        .operatorType(entry.getOperatorType())
                        .operatorId(maskOperator ? null : entry.getOperatorId())
                        .remark(entry.getRemark())
                        .createdAt(entry.getCreatedAt())
                        .build())
                .toList();
    }

    private void writeLog(Long afterSaleId, AfterSaleStatus from, AfterSaleStatus to,
                          String operatorType, String operatorId, String remark) {
        AfterSaleLogEntity log = new AfterSaleLogEntity();
        log.setAfterSaleId(afterSaleId);
        log.setFromStatus(from == null ? null : from.name());
        log.setToStatus(to.name());
        log.setOperatorType(operatorType);
        log.setOperatorId(operatorId);
        log.setRemark(remark);
        log.setCreatedAt(Times.now());
        afterSaleLogMapper.insert(log);
    }

    private OrderEntity requireOrder(Long orderId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        return order;
    }

    /**
     * 取订单行并校验它属于当前用户的订单。
     * <p>
     * 「不存在」与「不属于你」返回同一句话：区分开来等于告诉调用方哪些 id 有效。
     */
    private OrderItemEntity requireOwnedItem(String userId, Long orderItemId) {
        OrderItemEntity item = orderItemId == null ? null : orderItemMapper.selectById(orderItemId);
        if (item == null) {
            throw new IllegalArgumentException("订单商品不存在");
        }
        OrderEntity order = orderMapper.selectById(item.getOrderId());
        if (order == null || !order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("订单商品不存在");
        }
        return item;
    }

    private AfterSaleEntity requireOwned(String userId, Long afterSaleId) {
        AfterSaleEntity entity = afterSaleId == null ? null : afterSaleMapper.selectById(afterSaleId);
        if (entity == null || !entity.getUserId().equals(userId)) {
            throw new IllegalArgumentException("售后单不存在");
        }
        return entity;
    }

    private String generateNo() {
        return "AS" + DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(LocalDateTime.now())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private AfterSaleResponse toResponse(AfterSaleEntity entity, OrderItemEntity item,
                                         String docRef, Long maxRefundable) {
        AfterSaleStatus status = AfterSaleStatus.parse(entity.getStatus());
        OrderItemEntity snapshot = item;
        if (snapshot == null && entity.getOrderItemId() != null) {
            snapshot = orderItemMapper.selectById(entity.getOrderItemId());
        }

        List<String> images = entity.getImages() == null || entity.getImages().isBlank()
                ? List.of() : Arrays.stream(entity.getImages().split(",")).toList();

        return AfterSaleResponse.builder()
                .id(entity.getId())
                .afterSaleNo(entity.getAfterSaleNo())
                .orderId(entity.getOrderId())
                .orderNo(entity.getOrderNo())
                .orderItemId(entity.getOrderItemId())
                .userId(entity.getUserId())
                .type(entity.getType())
                .typeText(AfterSaleType.text(entity.getType()))
                .status(status.name())
                .statusText(status.text())
                .reason(entity.getReason())
                .description(entity.getDescription())
                .images(images)
                .refundAmount(entity.getRefundAmount())
                .maxRefundable(maxRefundable)
                .appliedAt(entity.getAppliedAt())
                .auditedAt(entity.getAuditedAt())
                .finishedAt(entity.getFinishedAt())
                .auditRemark(entity.getAuditRemark())
                .returnCarrier(entity.getReturnCarrier())
                .returnTrackingNo(entity.getReturnTrackingNo())
                .returnedAt(entity.getReturnedAt())
                .docRef(docRef)
                .spuName(snapshot == null ? null : snapshot.getSpuName())
                .skuSpecText(snapshot == null ? null : snapshot.getSkuSpecText())
                .skuImage(snapshot == null ? null : snapshot.getSkuImage())
                .build();
    }
}
