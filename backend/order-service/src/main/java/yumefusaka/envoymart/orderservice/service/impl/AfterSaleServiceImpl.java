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
import yumefusaka.envoymart.orderservice.client.PaymentClient;
import yumefusaka.envoymart.orderservice.entity.AfterSaleEntity;
import yumefusaka.envoymart.orderservice.entity.AfterSaleLogEntity;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;
import yumefusaka.envoymart.orderservice.entity.OrderItemEntity;
import yumefusaka.envoymart.orderservice.mapper.AfterSaleLogMapper;
import yumefusaka.envoymart.orderservice.mapper.AfterSaleMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderItemMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderMapper;
import yumefusaka.envoymart.contract.AfterSalePreview;
import yumefusaka.envoymart.orderservice.model.AfterSaleResponse;
import yumefusaka.envoymart.orderservice.model.AfterSaleStatus;
import yumefusaka.envoymart.orderservice.model.AfterSaleType;
import yumefusaka.envoymart.orderservice.model.ApplyAfterSaleRequest;
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
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AfterSaleServiceImpl implements AfterSaleService {

    private static final String OPERATOR_USER = "USER";
    private static final String OPERATOR_ADMIN = "ADMIN";

    /** 进行中的售后状态：同一订单行不允许同时有两个 */
    private static final List<String> ACTIVE_STATUSES = List.of(
            AfterSaleStatus.APPLIED.name(),
            AfterSaleStatus.APPROVED.name(),
            AfterSaleStatus.RETURNING.name(),
            AfterSaleStatus.RECEIVED.name(),
            AfterSaleStatus.REFUNDING.name());

    private final AfterSaleMapper afterSaleMapper;
    private final AfterSaleLogMapper afterSaleLogMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final AfterSalePolicyEngine policyEngine;
    private final PaymentClient paymentClient;

    public AfterSaleServiceImpl(AfterSaleMapper afterSaleMapper,
                                AfterSaleLogMapper afterSaleLogMapper,
                                OrderMapper orderMapper,
                                OrderItemMapper orderItemMapper,
                                AfterSalePolicyEngine policyEngine,
                                PaymentClient paymentClient) {
        this.afterSaleMapper = afterSaleMapper;
        this.afterSaleLogMapper = afterSaleLogMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.policyEngine = policyEngine;
        this.paymentClient = paymentClient;
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
                .in(AfterSaleEntity::getStatus, ACTIVE_STATUSES));
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

        if (current == AfterSaleStatus.REFUNDING || current.isTerminal()) {
            // 已经在打款了就不能撤 —— 撤了钱也会到账，状态和事实对不上
            throw new IllegalStateException("售后单当前状态为「" + current.text() + "」，不可撤销");
        }

        transit(entity, current, AfterSaleStatus.CANCELLED, OPERATOR_USER, userId, "用户撤销申请");
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
        transit(entity, current, AfterSaleStatus.RECEIVED, OPERATOR_ADMIN, operatorId, "商家已收到退货");
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
        Page<AfterSaleEntity> page = new Page<>(query.safePage(), query.safeSize());
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
                .page(query.safePage())
                .size(query.safeSize())
                .build();
    }

    @Override
    public AdminAfterSaleDetail adminDetail(Long afterSaleId) {
        AfterSaleEntity entity = requireAfterSale(afterSaleId);

        List<StatusLogView> logs = afterSaleLogMapper.selectList(
                        new LambdaQueryWrapper<AfterSaleLogEntity>()
                                .eq(AfterSaleLogEntity::getAfterSaleId, afterSaleId)
                                .orderByAsc(AfterSaleLogEntity::getId))
                .stream()
                .map(entry -> StatusLogView.builder()
                        .fromStatus(entry.getFromStatus())
                        .toStatus(entry.getToStatus())
                        .operatorType(entry.getOperatorType())
                        .operatorId(entry.getOperatorId())
                        .remark(entry.getRemark())
                        .createdAt(entry.getCreatedAt())
                        .build())
                .toList();

        return AdminAfterSaleDetail.builder()
                .afterSale(toResponse(entity, null, null, null))
                .logs(logs)
                .build();
    }

    private LambdaQueryWrapper<AfterSaleEntity> adminWrapper(AdminAfterSaleQuery query) {
        LambdaQueryWrapper<AfterSaleEntity> wrapper = new LambdaQueryWrapper<>();

        if (query.getUserId() != null && !query.getUserId().isBlank()) {
            wrapper.eq(AfterSaleEntity::getUserId, query.getUserId().trim());
        }
        List<String> statuses = query.statusList();
        if (!statuses.isEmpty()) {
            wrapper.in(AfterSaleEntity::getStatus, statuses);
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
    public AfterSaleResponse detail(String userId, Long afterSaleId) {
        return toResponse(requireOwned(userId, afterSaleId), null, null, null);
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

        refundFromRefunding(entity);
    }

    /**
     * 从「退款中」推进到「已完成」。
     * <p>
     * 失败**不抛出去**：抛出去会让整个事务回滚，「已审核通过」这个事实也会消失，
     * 而用户看到的是一次莫名其妙的失败。留在退款中并留 ERROR —— 那是一个人
     * 能接手的状态，配合 {@link #retryRefund} 可以重试。
     */
    private void refundFromRefunding(AfterSaleEntity entity) {
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
            transit(entity, AfterSaleStatus.REFUNDING, AfterSaleStatus.FINISHED,
                    "SYSTEM", null, "退款完成：" + (result.getData() == null ? "" : result.getData().getRefundNo()));
            log.info("售后退款完成: no={}, amount={}", entity.getAfterSaleNo(), entity.getRefundAmount());
        } catch (Exception e) {
            log.error("[AfterSale] 退款失败，售后单停留在退款中，可调用 retry-refund 重试 no={} orderNo={} amount={}: {}",
                    entity.getAfterSaleNo(), entity.getOrderNo(), entity.getRefundAmount(), e.getMessage());
        }
    }

    private void transit(AfterSaleEntity entity, AfterSaleStatus from, AfterSaleStatus to,
                         String operatorType, String operatorId, String remark) {
        int updated = afterSaleMapper.update(null, new LambdaUpdateWrapper<AfterSaleEntity>()
                .eq(AfterSaleEntity::getId, entity.getId())
                .eq(AfterSaleEntity::getStatus, from.name())
                .set(AfterSaleEntity::getStatus, to.name())
                .set(to == AfterSaleStatus.FINISHED, AfterSaleEntity::getFinishedAt, Times.now())
                .set(to == AfterSaleStatus.APPROVED || to == AfterSaleStatus.REJECTED,
                        AfterSaleEntity::getAuditedAt, Times.now())
                .set(to == AfterSaleStatus.REJECTED, AfterSaleEntity::getAuditRemark, remark));
        if (updated == 0) {
            // 状态被并发改过。条件更新是唯一能裁决它的地方
            throw new IllegalStateException("售后单状态刚刚发生变化，请刷新后重试");
        }
        entity.setStatus(to.name());
        writeLog(entity.getId(), from, to, operatorType, operatorId, remark);
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
                .docRef(docRef)
                .spuName(snapshot == null ? null : snapshot.getSpuName())
                .skuSpecText(snapshot == null ? null : snapshot.getSkuSpecText())
                .skuImage(snapshot == null ? null : snapshot.getSkuImage())
                .build();
    }
}
