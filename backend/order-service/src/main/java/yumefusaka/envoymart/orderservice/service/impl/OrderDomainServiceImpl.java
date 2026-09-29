package yumefusaka.envoymart.orderservice.service.impl;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.orderservice.client.ProductClient;
import yumefusaka.envoymart.orderservice.config.SentinelDegradeConfig;
import yumefusaka.envoymart.orderservice.entity.CartItemEntity;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;
import yumefusaka.envoymart.orderservice.entity.OrderItemEntity;
import yumefusaka.envoymart.orderservice.mapper.CartItemMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderItemMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderMapper;
import yumefusaka.envoymart.orderservice.model.CheckoutRequest;
import yumefusaka.envoymart.orderservice.model.LogisticsResponse;
import yumefusaka.envoymart.orderservice.model.LogisticsStepResponse;
import yumefusaka.envoymart.orderservice.model.OrderItemResponse;
import yumefusaka.envoymart.orderservice.model.OrderResponse;
import yumefusaka.envoymart.orderservice.model.OrderStatus;
import yumefusaka.envoymart.orderservice.model.SkuSnapshot;
import yumefusaka.envoymart.orderservice.model.StockChangeRequest;
import yumefusaka.envoymart.orderservice.mq.OrderCreatedEvent;
import yumefusaka.envoymart.orderservice.mq.OrderEventPublisher;
import yumefusaka.envoymart.orderservice.mq.OrderItemEvent;
import yumefusaka.envoymart.orderservice.service.OrderDomainService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderDomainServiceImpl implements OrderDomainService {

    /** 下单后多久未支付就关单。前端据此显示倒计时，定时任务据此扫描 */
    private static final int PAYMENT_WINDOW_MINUTES = 30;
    /** 全场包邮：运费先留字段，等有运费规则时再填 */
    private static final long FREIGHT_FREE = 0L;

    private static final String BIZ_TYPE_ORDER = "ORDER";

    private final CartItemMapper cartItemMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductClient productClient;
    private final CartCacheService cartCacheService;
    private final OrderEventPublisher eventPublisher;

    public OrderDomainServiceImpl(CartItemMapper cartItemMapper,
                                  OrderMapper orderMapper,
                                  OrderItemMapper orderItemMapper,
                                  ProductClient productClient,
                                  CartCacheService cartCacheService,
                                  OrderEventPublisher eventPublisher) {
        this.cartItemMapper = cartItemMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productClient = productClient;
        this.cartCacheService = cartCacheService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @GlobalTransactional(name = "envoymart-checkout", rollbackFor = Exception.class)
    @Transactional
    public OrderResponse checkout(String userId, CheckoutRequest request) {
        // 只结算**勾选**的条目：购物车里可以有暂不买的商品
        List<CartItemEntity> cartItems = selectSelected(userId);
        if (cartItems.isEmpty()) {
            throw new IllegalArgumentException("请先勾选要结算的商品");
        }

        // 加锁范围必须与释放范围一致。若只把"用锁"的那段包进 try，加锁过程中途失败
        // （并发抢锁超时、Feign 报错、线程中断）会让已经拿到的锁一直不释放，
        // 只能等租期自然过期，期间同一 SKU 的其他用户全部下单失败。
        List<Long> lockedSkuIds = new ArrayList<>();
        OrderEntity order = null;
        long total = 0L;
        List<OrderItemEvent> eventItems = new ArrayList<>();
        // 记账本：已经成功扣掉的库存，失败时按相反顺序还回去
        List<StockChangeRequest> deducted = new ArrayList<>();

        try {
            for (CartItemEntity cartItem : cartItems) {
                if (!cartCacheService.tryLock(cartItem.getSkuId())) {
                    throw new IllegalStateException("商品「" + resolveName(cartItem.getSkuId())
                            + "」当前购买人数过多，请稍后再试");
                }
                // 拿锁成功才记账：失败的那把本就没拿到，不需要（也不能）释放
                lockedSkuIds.add(cartItem.getSkuId());
            }

            // 先锁后重读：加锁前读到的是陈旧快照，并发的另一次下单可能已经清空了购物车。
            // 不重读的话锁形同虚设 —— 拿着旧快照继续扣一次库存、再建一张单
            cartItems = selectSelected(userId);
            if (cartItems.isEmpty()) {
                throw new IllegalArgumentException("请先勾选要结算的商品");
            }

            order = new OrderEntity();
            order.setOrderNo(generateOrderNo());
            order.setUserId(userId);
            order.setStatus(OrderStatus.CREATED.name());
            order.setReceiverName(request.getReceiverName());
            order.setReceiverPhone(request.getReceiverPhone());
            order.setReceiverProvince(request.getReceiverProvince());
            order.setReceiverCity(request.getReceiverCity());
            order.setReceiverDistrict(request.getReceiverDistrict());
            order.setReceiverDetail(request.getReceiverDetail());
            order.setRemark(request.getRemark());
            order.setFreightAmount(FREIGHT_FREE);
            order.setDiscountAmount(0L);
            order.setTotalAmount(0L);
            order.setPayAmount(0L);
            LocalDateTime now = Times.now();
            order.setCreatedAt(now);
            order.setExpireAt(now.plusMinutes(PAYMENT_WINDOW_MINUTES));
            orderMapper.insert(order);

            Map<Long, SkuSnapshot> skus = fetchSkus(
                    cartItems.stream().map(CartItemEntity::getSkuId).toList());

            try {
                for (CartItemEntity cartItem : cartItems) {
                    SkuSnapshot sku = skus.get(cartItem.getSkuId());
                    if (sku == null) {
                        throw new IllegalArgumentException("购物车中有商品已下架，请刷新后重试");
                    }
                    // 扣减失败要**抛异常**：只记日志的话订单照建、库存不扣，
                    // 而整条链路不会报任何错
                    deductStockWithCircuitBreaker(sku.getId(), cartItem.getQuantity(),
                            sku.getSpuName(), order.getOrderNo());
                    deducted.add(StockChangeRequest.builder()
                            .skuId(sku.getId())
                            .quantity(cartItem.getQuantity())
                            .bizType(BIZ_TYPE_ORDER)
                            .bizId(order.getOrderNo())
                            .build());

                    long subtotal = sku.getPrice() * cartItem.getQuantity();
                    total += subtotal;

                    OrderItemEntity item = new OrderItemEntity();
                    item.setOrderId(order.getId());
                    item.setOrderNo(order.getOrderNo());
                    item.setSpuId(sku.getSpuId());
                    item.setSkuId(sku.getId());
                    // 快照：商品改名改价之后，历史订单必须还原当时的样子
                    item.setSpuName(sku.getSpuName());
                    item.setSkuSpecText(sku.getSpecText());
                    item.setSkuImage(sku.getImage());
                    item.setUnitPrice(sku.getPrice());
                    item.setQuantity(cartItem.getQuantity());
                    item.setSubtotal(subtotal);
                    orderItemMapper.insert(item);

                    eventItems.add(OrderItemEvent.builder()
                            .skuId(sku.getId())
                            .skuName(sku.getSpuName())
                            .quantity(cartItem.getQuantity())
                            .price(sku.getPrice())
                            .build());
                }

                order.setTotalAmount(total);
                order.setPayAmount(total + order.getFreightAmount() - order.getDiscountAmount());
                orderMapper.updateById(order);
            } catch (RuntimeException e) {
                compensateStock(deducted);
                throw e;
            }

            // 只清掉已结算的条目，未勾选的留在车里
            cartItemMapper.delete(new LambdaQueryWrapper<CartItemEntity>()
                    .eq(CartItemEntity::getUserId, userId)
                    .eq(CartItemEntity::getSelected, 1));
            cartCacheService.evictCartCache(userId);
            log.info("用户 {} 下单成功，订单号 {}，应付 {} 分", userId, order.getOrderNo(), order.getPayAmount());
        } finally {
            // 逆序释放，与加锁顺序相反，降低与其他事务交叉持锁时死锁的概率
            for (int i = lockedSkuIds.size() - 1; i >= 0; i--) {
                cartCacheService.unlock(lockedSkuIds.get(i));
            }
        }

        // 事件发布挪到**锁外**：它是"可以重来的副作用"，没有理由占着库存锁。
        // 一次 MQ 发布是一次网络往返，留在锁里就直接计入临界区时长 ——
        // 而「临界区时长 × 并发数」正是队尾请求要等的时间
        publishOrderCreatedQuietly(order, userId, total, eventItems);

        return getOrder(userId, order.getId());
    }

    private List<CartItemEntity> selectSelected(String userId) {
        return cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .eq(CartItemEntity::getSelected, 1));
    }

    private String resolveName(Long skuId) {
        SkuSnapshot sku = fetchSkus(List.of(skuId)).get(skuId);
        return sku == null ? String.valueOf(skuId) : sku.getSpuName();
    }

    private String generateOrderNo() {
        return "YS" + DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(LocalDateTime.now())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    /**
     * 发布订单创建事件，失败只记日志。
     * <p>
     * <b>不能让它影响主流程</b>：订单已经建好，本地事务也已经提交，事件丢了只该留一条 ERROR。
     * 这个异常如果抛出去，本地事务会回滚、订单不建，**而 product-service 那边扣掉的库存
     * 已经提交、没有任何人回补** —— 实测并发下单时整仓库存被这样刷空过。
     * <p>
     * 调用点刻意放在加锁的 try/finally 之外，所以这里不需要考虑锁的释放。
     */
    private void publishOrderCreatedQuietly(OrderEntity order, String userId,
                                            Long total, List<OrderItemEvent> items) {
        try {
            eventPublisher.publishOrderCreated(OrderCreatedEvent.builder()
                    .orderId(order.getId())
                    .orderNo(order.getOrderNo())
                    .userId(userId)
                    .totalAmount(total)
                    .items(items)
                    .createdAt(order.getCreatedAt())
                    .build());
        } catch (Exception e) {
            log.error("[Order] 事件发布失败，订单已创建但下游不会收到通知: orderNo={}",
                    order.getOrderNo(), e);
        }
    }

    @Override
    public List<OrderResponse> listOrders(String userId) {
        return orderMapper.selectList(new LambdaQueryWrapper<OrderEntity>()
                        .eq(OrderEntity::getUserId, userId)
                        .orderByDesc(OrderEntity::getCreatedAt))
                .stream()
                .map(this::toOrderResponse)
                .toList();
    }

    @Override
    public OrderResponse getOrder(String userId, Long orderId) {
        OrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getUserId, userId)
                .eq(OrderEntity::getId, orderId));
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        return toOrderResponse(order);
    }

    /**
     * 物流轨迹。
     * <p>
     * <b>当前是按订单时间推算出来的演示数据</b>，没有对接任何承运商 ——
     * 真实的轨迹来自承运商推送，落在 {@code order_delivery_trace} 表里。
     * 这里如实标注，不假装它是真的。
     */
    @Override
    public LogisticsResponse getLogistics(String userId, Long orderId) {
        OrderResponse order = getOrder(userId, orderId);
        LocalDateTime createdAt = order.getCreatedAt();
        return LogisticsResponse.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .carrier("演示承运商")
                .trackingNo("DEMO" + order.getOrderNo().substring(2, 12))
                .steps(List.of(
                        LogisticsStepResponse.builder().status("已下单").detail("订单已创建，等待付款")
                                .time(createdAt).build(),
                        LogisticsStepResponse.builder().status("已出库").detail("包裹已完成打包并离开仓库")
                                .time(createdAt.plusHours(4)).build(),
                        LogisticsStepResponse.builder().status("运输中").detail("包裹正在前往目的城市分拨中心")
                                .time(createdAt.plusHours(18)).build(),
                        LogisticsStepResponse.builder().status("派送中").detail("快递员正在派送，请保持电话畅通")
                                .time(createdAt.plusDays(1)).build()))
                .build();
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(String userId, Long orderId) {
        OrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getId, orderId)
                .eq(OrderEntity::getUserId, userId));
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }

        OrderStatus current = OrderStatus.parse(order.getStatus());
        // 只有未支付的订单能直接取消。已支付的要走退款流程 —— 直接取消会让钱货两空，
        // 而那条流程还没做，所以这里如实拒绝而不是假装能办
        if (current != OrderStatus.CREATED) {
            if (current.isTerminal()) {
                throw new IllegalStateException("订单已" + statusText(current) + "，无需重复操作");
            }
            throw new IllegalStateException("订单已支付，请走退款流程");
        }

        // 状态判断下沉到 SQL 的 where 里，由数据库裁决并发，而不是在内存里"读-判断-写"。
        // 纯内存判断挡不住并发：两个取消请求各自读到 CREATED，双双通过守卫，
        // 于是一笔订单回补两次库存 —— 实测 8 个并发取消，库存比正确值多出整整一倍。
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                .eq(OrderEntity::getId, orderId)
                .eq(OrderEntity::getStatus, current.name())
                .set(OrderEntity::getStatus, OrderStatus.CANCELLED.name())
                .set(OrderEntity::getClosedAt, Times.now()));
        if (updated == 0) {
            throw new IllegalStateException("订单状态刚刚发生变化，请刷新后重试");
        }
        order.setStatus(OrderStatus.CANCELLED.name());

        restoreStockQuietly(order);
        log.info("用户 {} 取消订单 {}", userId, order.getOrderNo());
        return toOrderResponse(order);
    }

    /**
     * 关掉超时未支付的订单并回补库存。
     * <p>
     * 原先没有这件事：未支付订单永久停在原状态，而库存在下单时就已经扣掉了 ——
     * 库存被永久占用，越积越多，且没有任何地方会报错。
     * <p>
     * 做成幂等的：同一条订单被扫到两次时，第二次的条件更新会返回 0，直接跳过。
     *
     * @return 本次真正关掉的订单数
     */
    @Transactional
    public int closeExpiredOrders(int batchSize) {
        List<OrderEntity> expired = orderMapper.selectList(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getStatus, OrderStatus.CREATED.name())
                .lt(OrderEntity::getExpireAt, Times.now())
                .last("limit " + batchSize));
        int closed = 0;
        for (OrderEntity order : expired) {
            int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                    .eq(OrderEntity::getId, order.getId())
                    .eq(OrderEntity::getStatus, OrderStatus.CREATED.name())
                    .set(OrderEntity::getStatus, OrderStatus.CLOSED.name())
                    .set(OrderEntity::getClosedAt, Times.now())
                    .set(OrderEntity::getCancelReason, "超时未支付"));
            if (updated == 0) {
                continue;
            }
            order.setStatus(OrderStatus.CLOSED.name());
            restoreStockQuietly(order);
            closed++;
        }
        if (closed > 0) {
            log.info("[Order] 超时关单 {} 笔，库存已回补", closed);
        }
        return closed;
    }

    /**
     * 回补订单占用的库存。
     * <p>
     * <b>失败不向上抛</b>：多件商品时前面的可能已经回补成功，抛出去只会让订单状态退回去，
     * 而库存已经还了一半 —— 变成"库存凭空多出来"。但必须以 ERROR 留痕：
     * 静默吞掉会让库存越差越多且无人察觉。
     */
    private void restoreStockQuietly(OrderEntity order) {
        List<OrderItemEntity> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItemEntity>().eq(OrderItemEntity::getOrderId, order.getId()));
        for (OrderItemEntity item : items) {
            try {
                requireSuccess(productClient.restoreStock(StockChangeRequest.builder()
                                .skuId(item.getSkuId())
                                .quantity(item.getQuantity())
                                .bizType(BIZ_TYPE_ORDER)
                                .bizId(order.getOrderNo())
                                .remark("订单关闭回补")
                                .build()),
                        "回补库存 skuId=" + item.getSkuId());
            } catch (Exception e) {
                log.error("回补库存失败，订单已关闭但库存未归还 orderId={} skuId={} quantity={}: {}",
                        order.getId(), item.getSkuId(), item.getQuantity(), e.getMessage());
            }
        }
    }

    @Override
    @Transactional
    public void markPaid(Long orderId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            log.error("[Order] 收到支付完成事件但订单不存在 orderId={}", orderId);
            return;
        }
        OrderStatus current = OrderStatus.parse(order.getStatus());
        if (current == OrderStatus.PAID) {
            return;  // 重复投递，幂等吞掉
        }
        if (current.isTerminal()) {
            // 钱收了、单却取消了 —— 这是资金问题，留明确记录等人工退款，
            // 不能自动改成 PAID 把矛盾掩盖过去
            log.error("[Order] 订单已{}却收到支付完成事件，需要人工退款 orderId={} orderNo={}",
                    statusText(current), orderId, order.getOrderNo());
            return;
        }
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                .eq(OrderEntity::getId, orderId)
                .eq(OrderEntity::getStatus, current.name())
                .set(OrderEntity::getStatus, OrderStatus.PAID.name())
                .set(OrderEntity::getPaidAt, Times.now()));
        if (updated == 0) {
            log.warn("[Order] 订单状态并发变更，支付完成事件未生效 orderId={}", orderId);
            return;
        }
        log.info("[Order] 订单已标记为已支付 orderId={} orderNo={}", orderId, order.getOrderNo());
    }

    private Map<Long, SkuSnapshot> fetchSkus(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        Result<List<SkuSnapshot>> response = productClient.getSkus(skuIds);
        if (response == null || response.getData() == null) {
            throw new IllegalStateException("商品服务暂时不可用，请稍后再试");
        }
        return response.getData().stream()
                .collect(Collectors.toMap(SkuSnapshot::getId, Function.identity(), (a, b) -> a));
    }

    /**
     * 反序归还已扣减的库存。
     * <p>
     * 反序是为了与扣减顺序相反，和加解锁的约定一致，降低与其他事务交叉时的死锁概率。
     * 补偿本身再失败就只能留 ERROR：远端已经提交，本地既无法回滚也没有重试机制，
     * 属于需要人工介入的最终一致性问题 —— 宁可吵，不可静默。
     */
    private void compensateStock(List<StockChangeRequest> deducted) {
        for (int i = deducted.size() - 1; i >= 0; i--) {
            StockChangeRequest request = deducted.get(i);
            try {
                requireSuccess(productClient.restoreStock(request),
                        "补偿回补库存 skuId=" + request.getSkuId());
                log.warn("[Order] 下单失败，已回补库存 skuId={} quantity={}",
                        request.getSkuId(), request.getQuantity());
            } catch (Exception e) {
                log.error("[Order] 下单失败且库存补偿失败，需要人工处理 skuId={} quantity={}: {}",
                        request.getSkuId(), request.getQuantity(), e.getMessage());
            }
        }
    }

    /**
     * 校验跨服务调用的<b>业务码</b>，而不是 HTTP 状态码。
     * <p>
     * 本项目用 {@code HTTP 200 + Result.code} 表达业务结果，异常也被统一包成 200 + code=500。
     * Feign 只看 HTTP 状态码，**下游报错时它不会抛异常，只会安静地返回一个 code=500 的对象**。
     * 不显式检查，任何下游失败都会被当成成功。
     */
    private void requireSuccess(Result<?> result, String action) {
        if (result == null || result.getCode() == null || result.getCode() != 200) {
            throw new IllegalStateException(action + "失败：" + (result == null ? "无响应" : result.getMsg()));
        }
    }

    /**
     * 扣减库存，带熔断保护。
     * <p>
     * <b>熔断与超时解决的不是一回事</b>：只有超时（read 5s）时，下游挂掉后每个请求
     * 仍要干等 5 秒才失败 —— 并发一上来，调用方线程先被占满，故障从下游蔓延到上游。
     * 熔断打开后直接拒绝，不占用等待时间。
     * <p>
     * <b>熔断后刻意不降级为"成功"</b>：库存扣减没有这个选项，扣不了就是不能下单。
     */
    private void deductStockWithCircuitBreaker(Long skuId, Integer quantity,
                                               String productName, String orderNo) {
        Entry entry = null;
        try {
            entry = SphU.entry(SentinelDegradeConfig.RESOURCE_DEDUCT_STOCK);
            requireSuccess(productClient.deductStock(StockChangeRequest.builder()
                            .skuId(skuId)
                            .quantity(quantity)
                            .bizType(BIZ_TYPE_ORDER)
                            .bizId(orderNo)
                            .build()),
                    "扣减库存 " + productName);
        } catch (BlockException e) {
            log.warn("[Sentinel] 扣减库存被熔断: skuId={}", skuId);
            throw new IllegalStateException("库存服务暂时不可用，请稍后重试");
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    private OrderResponse toOrderResponse(OrderEntity order) {
        List<OrderItemResponse> items = orderItemMapper.selectList(
                        new LambdaQueryWrapper<OrderItemEntity>()
                                .eq(OrderItemEntity::getOrderId, order.getId()))
                .stream()
                .map(item -> OrderItemResponse.builder()
                        .id(item.getId())
                        .spuId(item.getSpuId())
                        .skuId(item.getSkuId())
                        .spuName(item.getSpuName())
                        .skuSpecText(item.getSkuSpecText())
                        .skuImage(item.getSkuImage())
                        .unitPrice(item.getUnitPrice())
                        .quantity(item.getQuantity())
                        .subtotal(item.getSubtotal())
                        .build())
                .toList();

        OrderStatus status = OrderStatus.parse(order.getStatus());
        return OrderResponse.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .status(status.name())
                .statusText(statusText(status))
                .totalAmount(order.getTotalAmount())
                .freightAmount(order.getFreightAmount())
                .discountAmount(order.getDiscountAmount())
                .payAmount(order.getPayAmount())
                .receiverName(order.getReceiverName())
                .receiverPhone(order.getReceiverPhone())
                .receiverProvince(order.getReceiverProvince())
                .receiverCity(order.getReceiverCity())
                .receiverDistrict(order.getReceiverDistrict())
                .receiverDetail(order.getReceiverDetail())
                .expireAt(order.getExpireAt())
                .createdAt(order.getCreatedAt())
                .paidAt(order.getPaidAt())
                .shippedAt(order.getShippedAt())
                .receivedAt(order.getReceivedAt())
                .closedAt(order.getClosedAt())
                .remark(order.getRemark())
                .cancelReason(order.getCancelReason())
                .items(items)
                .build();
    }

    /**
     * 状态的中文说明。
     * <p>
     * 放在服务端而不是前端：状态集合会变，散在客户端的那份迟早与后端不一致 ——
     * 而那时用户看到的是一个没人认识的状态名。
     */
    private String statusText(OrderStatus status) {
        return switch (status) {
            case CREATED -> "待支付";
            case PAID -> "待发货";
            case SHIPPED -> "已发货";
            case RECEIVED -> "已收货";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            case CLOSED -> "已关闭";
            case REFUNDING -> "退款中";
            case REFUNDED -> "已退款";
        };
    }
}
