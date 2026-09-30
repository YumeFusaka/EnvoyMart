package yumefusaka.envoymart.orderservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.orderservice.entity.OrderDeliveryEntity;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;
import yumefusaka.envoymart.orderservice.entity.OrderItemEntity;
import yumefusaka.envoymart.orderservice.entity.OrderStatusLogEntity;
import yumefusaka.envoymart.orderservice.mapper.OrderDeliveryMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderItemMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderStatusLogMapper;
import yumefusaka.envoymart.orderservice.model.OrderStatus;
import yumefusaka.envoymart.orderservice.model.admin.AdminOrderDetail;
import yumefusaka.envoymart.orderservice.model.admin.AdminOrderQuery;
import yumefusaka.envoymart.orderservice.model.admin.AdminOrderSummary;
import yumefusaka.envoymart.orderservice.model.admin.AdminShipRequest;
import yumefusaka.envoymart.orderservice.model.admin.StatusLogView;
import yumefusaka.envoymart.orderservice.service.OrderAdminService;
import yumefusaka.envoymart.orderservice.service.OrderDomainService;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单域的管理侧实现。
 * <p>
 * <b>它不自己实现发货</b>：那件事已经在 {@link OrderDomainService#shipOrder} 里，
 * 连同条件更新、履约单、首条轨迹一起。管理侧只是换了一个入口和一份操作人身份，
 * 复制一遍状态流转就等于埋了第二份会对不上的真相。
 */
@Slf4j
@Service
public class OrderAdminServiceImpl implements OrderAdminService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final OrderDeliveryMapper deliveryMapper;
    private final OrderDomainService orderDomainService;

    public OrderAdminServiceImpl(OrderMapper orderMapper,
                                 OrderItemMapper orderItemMapper,
                                 OrderStatusLogMapper statusLogMapper,
                                 OrderDeliveryMapper deliveryMapper,
                                 OrderDomainService orderDomainService) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.statusLogMapper = statusLogMapper;
        this.deliveryMapper = deliveryMapper;
        this.orderDomainService = orderDomainService;
    }

    @Override
    public PageResult<AdminOrderSummary> list(AdminOrderQuery query) {
        Page<OrderEntity> page = new Page<>(query.mpCurrent(), query.safeSize());
        Page<OrderEntity> result = orderMapper.selectPage(page, buildWrapper(query));

        List<OrderEntity> orders = result.getRecords();
        // 订单行与履约单**一次查完这一页的**，不是每行查一次。
        // 逐行查是管理列表最典型的性能坑：一页 20 行就是 41 次查询，
        // 而它在数据量小的时候完全看不出问题
        Map<Long, List<OrderItemEntity>> itemsByOrder = itemsOf(orders);
        Map<Long, OrderDeliveryEntity> deliveryByOrder = deliveriesOf(orders);

        List<AdminOrderSummary> records = orders.stream()
                .map(order -> toSummary(order,
                        itemsByOrder.getOrDefault(order.getId(), List.of()),
                        deliveryByOrder.get(order.getId())))
                .toList();

        return PageResult.<AdminOrderSummary>builder()
                .records(records)
                .total(result.getTotal())
                .page(query.zeroBasedPage())
                .size(query.safeSize())
                .build();
    }

    @Override
    public AdminOrderDetail detail(Long orderId) {
        OrderEntity order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }

        List<StatusLogView> logs = statusLogMapper.selectList(
                        new LambdaQueryWrapper<OrderStatusLogEntity>()
                                .eq(OrderStatusLogEntity::getOrderId, orderId)
                                .orderByAsc(OrderStatusLogEntity::getId))
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

        return AdminOrderDetail.builder()
                .order(orderDomainService.orderById(orderId))
                // 未发货时 logisticsOf 返回的是「有订单、没有轨迹」而不是 null：
                // 详情页要能区分「还没发货」和「这个接口挂了」
                .delivery(orderDomainService.logisticsOf(orderId))
                .adminRemark(order.getAdminRemark())
                .statusLogs(logs)
                .build();
    }

    @Override
    public AdminOrderSummary ship(Long orderId, AdminShipRequest request, String operatorId) {
        orderDomainService.shipOrder(orderId, request.getCarrierCode(), request.getCarrierName(),
                request.getTrackingNo(), operatorId);
        return summaryOf(orderId);
    }

    @Override
    @Transactional
    public AdminOrderSummary remark(Long orderId, String remark) {
        OrderEntity order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }

        // 空串归一成 null：库里「没备注」只该有一种表示，两种会让
        // 「哪些单子有备注」这个问题有两个答案
        String normalized = remark == null || remark.isBlank() ? null : remark.trim();
        orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                .eq(OrderEntity::getId, orderId)
                .set(OrderEntity::getAdminRemark, normalized));

        log.info("[OrderAdmin] 商家备注已更新 orderNo={} hasRemark={}", order.getOrderNo(),
                normalized != null);
        return summaryOf(orderId);
    }

    /**
     * 单条订单的管理端视图 —— 写操作之后的回执。
     * <p>
     * 管理台点完「发货」要就地更新那一行。返回空值等于逼前端再发一次列表请求，
     * 而那一行在分页里未必还落在同一页（并发下单会把它挤到下一页去）。
     * <p>
     * 没有另写一份字段映射：把单条包成一个元素的列表，复用列表那套批量取数，
     * 免得两处各自演化出「管理端该看到哪些字段」的第二份定义。
     */
    private AdminOrderSummary summaryOf(Long orderId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        List<OrderEntity> single = List.of(order);
        return toSummary(order, itemsOf(single).getOrDefault(orderId, List.of()),
                deliveriesOf(single).get(orderId));
    }

    private LambdaQueryWrapper<OrderEntity> buildWrapper(AdminOrderQuery query) {
        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<>();

        if (hasText(query.getUserId())) {
            wrapper.eq(OrderEntity::getUserId, query.getUserId());
        }
        if (hasText(query.getStatus())) {
            // 取值非法时 OrderStatus.parse 抛 IllegalArgumentException → 400。
            // 不能静默忽略：页面会一本正经地展示「全部订单」，而看的人以为筛过了
            wrapper.eq(OrderEntity::getStatus, OrderStatus.parse(query.getStatus()).name());
        }
        if (query.getCreatedFrom() != null) {
            wrapper.ge(OrderEntity::getCreatedAt, query.getCreatedFrom());
        }
        if (query.getCreatedTo() != null) {
            wrapper.le(OrderEntity::getCreatedAt, query.getCreatedTo());
        }
        if (hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            // 这三个条件是 **或** 的关系，必须整体包进 and(...)：
            // or 的优先级低于 and，散着写会让上面的 userId / status / 时间条件
            // 一起被短路掉 —— 于是「按状态筛选 + 关键词搜索」会返回别的状态的订单
            wrapper.and(w -> w.like(OrderEntity::getOrderNo, keyword)
                    .or().like(OrderEntity::getReceiverName, keyword)
                    .or().like(OrderEntity::getReceiverPhone, keyword));
        }

        // 排序必须带一个唯一的兜底列。只按 created_at 排时，同一秒创建的两张单
        // 在数据库眼里没有确定的先后，翻页会**同时漏掉一条、重复另一条** ——
        // 而这个现象只在有并发下单时出现，看起来像随机丢数据
        return wrapper.orderByDesc(OrderEntity::getCreatedAt).orderByDesc(OrderEntity::getId);
    }

    private Map<Long, List<OrderItemEntity>> itemsOf(List<OrderEntity> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        List<Long> orderIds = orders.stream().map(OrderEntity::getId).toList();
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItemEntity>()
                        .in(OrderItemEntity::getOrderId, orderIds))
                .stream()
                .collect(Collectors.groupingBy(OrderItemEntity::getOrderId));
    }

    private Map<Long, OrderDeliveryEntity> deliveriesOf(List<OrderEntity> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        List<Long> orderIds = orders.stream().map(OrderEntity::getId).toList();
        return deliveryMapper.selectList(new LambdaQueryWrapper<OrderDeliveryEntity>()
                        .in(OrderDeliveryEntity::getOrderId, orderIds))
                .stream()
                .collect(Collectors.toMap(OrderDeliveryEntity::getOrderId, Function.identity(),
                        // 一个订单按设计只有一条履约单（表上有唯一约束）。真出现两条时
                        // 取第一条而不是让整个列表 500 —— 列表页不该为一条脏数据罢工
                        (first, second) -> first));
    }

    private AdminOrderSummary toSummary(OrderEntity order, List<OrderItemEntity> items,
                                        OrderDeliveryEntity delivery) {
        OrderStatus status = OrderStatus.parse(order.getStatus());
        int totalQuantity = items.stream()
                .map(OrderItemEntity::getQuantity)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        return AdminOrderSummary.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .status(status.name())
                .statusText(status.text())
                .totalAmount(order.getTotalAmount())
                .discountAmount(order.getDiscountAmount())
                .payAmount(order.getPayAmount())
                .receiverName(order.getReceiverName())
                .receiverPhone(order.getReceiverPhone())
                .itemCount(items.size())
                .totalQuantity(totalQuantity)
                .firstItemName(items.isEmpty() ? null : items.get(0).getSpuName())
                .trackingNo(delivery == null ? null : delivery.getTrackingNo())
                .carrierName(delivery == null ? null : delivery.getCarrierName())
                .createdAt(order.getCreatedAt())
                .paidAt(order.getPaidAt())
                .shippedAt(order.getShippedAt())
                .adminRemark(order.getAdminRemark())
                .build();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
