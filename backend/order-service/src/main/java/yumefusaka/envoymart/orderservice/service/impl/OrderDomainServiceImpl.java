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
import yumefusaka.envoymart.orderservice.client.PaymentClient;
import yumefusaka.envoymart.orderservice.client.ProductClient;
import yumefusaka.envoymart.orderservice.client.PromotionClient;
import yumefusaka.envoymart.orderservice.config.SentinelDegradeConfig;
import yumefusaka.envoymart.orderservice.entity.AfterSaleEntity;
import yumefusaka.envoymart.orderservice.entity.CartItemEntity;
import yumefusaka.envoymart.orderservice.entity.OrderDeliveryEntity;
import yumefusaka.envoymart.orderservice.entity.OrderDeliveryTraceEntity;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;
import yumefusaka.envoymart.orderservice.entity.OrderItemEntity;
import yumefusaka.envoymart.orderservice.entity.OrderStatusLogEntity;
import yumefusaka.envoymart.orderservice.mapper.AfterSaleMapper;
import yumefusaka.envoymart.orderservice.mapper.CartItemMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderDeliveryMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderDeliveryTraceMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderItemMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderStatusLogMapper;
import yumefusaka.envoymart.orderservice.model.CheckoutRequest;
import yumefusaka.envoymart.contract.LogisticsResponse;
import yumefusaka.envoymart.contract.LogisticsStepResponse;
import yumefusaka.envoymart.contract.OrderItemResponse;
import yumefusaka.envoymart.contract.OrderResponse;
import yumefusaka.envoymart.orderservice.model.AfterSaleStatus;
import yumefusaka.envoymart.orderservice.model.OrderStatus;
import yumefusaka.envoymart.contract.RedeemItem;
import yumefusaka.envoymart.contract.RedeemRequest;
import yumefusaka.envoymart.contract.RefundRequest;
import yumefusaka.envoymart.contract.RefundResponse;
import yumefusaka.envoymart.contract.SkuSnapshot;
import yumefusaka.envoymart.contract.StockChangeRequest;
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
    /** 收货后多久自动完成。与售后政策的最长时限呼应：交易正式结束，售后入口随政策窗关闭 */
    private static final int AUTO_COMPLETE_DAYS = 7;
    /** 全场包邮：运费先留字段，等有运费规则时再填 */
    private static final long FREIGHT_FREE = 0L;

    private static final String BIZ_TYPE_ORDER = "ORDER";

    /** 商品服务里「在售」的取值 */
    private static final int STATUS_ON = 1;

    private final CartItemMapper cartItemMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStatusLogMapper orderStatusLogMapper;
    private final OrderDeliveryMapper deliveryMapper;
    private final OrderDeliveryTraceMapper deliveryTraceMapper;
    private final AfterSaleMapper afterSaleMapper;
    private final ProductClient productClient;
    private final PaymentClient paymentClient;
    private final PromotionClient promotionClient;
    private final CartCacheService cartCacheService;
    private final OrderEventPublisher eventPublisher;

    public OrderDomainServiceImpl(CartItemMapper cartItemMapper,
                                  OrderMapper orderMapper,
                                  OrderItemMapper orderItemMapper,
                                  OrderStatusLogMapper orderStatusLogMapper,
                                  OrderDeliveryMapper deliveryMapper,
                                  OrderDeliveryTraceMapper deliveryTraceMapper,
                                  AfterSaleMapper afterSaleMapper,
                                  ProductClient productClient,
                                  PaymentClient paymentClient,
                                  PromotionClient promotionClient,
                                  CartCacheService cartCacheService,
                                  OrderEventPublisher eventPublisher) {
        this.cartItemMapper = cartItemMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.orderStatusLogMapper = orderStatusLogMapper;
        this.deliveryMapper = deliveryMapper;
        this.deliveryTraceMapper = deliveryTraceMapper;
        this.afterSaleMapper = afterSaleMapper;
        this.productClient = productClient;
        this.paymentClient = paymentClient;
        this.promotionClient = promotionClient;
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
        // 传给券服务的订单行明细：限类目/限商品的券按「范围内商品小计」判门槛、算折扣
        List<RedeemItem> redeemItems = new ArrayList<>();

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

            // 提前把所有 SKU 查出来并挡住失效项。
            // **必须在建单之前**：建完单再发现商品下架，事务会回滚，但用户拿到的提示
            // 是一句没有指向的「库存不足」，而真正的原因（某件商品已下架）看不到。
            // 而且失效条目可能根本没出现在前端的结算页上 —— 用户连是哪件都不知道
            Map<Long, SkuSnapshot> skus = fetchSkus(
                    cartItems.stream().map(CartItemEntity::getSkuId).toList());
            long unavailable = cartItems.stream()
                    .map(CartItemEntity::getSkuId)
                    .filter(skuId -> {
                        SkuSnapshot sku = skus.get(skuId);
                        return sku == null || sku.getStatus() == null
                                || sku.getStatus() != STATUS_ON;
                    })
                    .count();
            if (unavailable > 0) {
                throw new IllegalStateException("购物车中有 " + unavailable
                        + " 件商品已下架，请到购物车中移除后再结算");
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
            // 记下用的哪张券：取消/关单/退款时要靠它把券退回去。
            // 光有 discountAmount 退不了券 —— 它只是一笔钱，对不回具体是哪张券
            order.setUserCouponId(request.getUserCouponId());
            order.setFreightAmount(FREIGHT_FREE);
            order.setTotalAmount(0L);
            order.setPayAmount(0L);
            LocalDateTime now = Times.now();
            order.setCreatedAt(now);
            order.setExpireAt(now.plusMinutes(PAYMENT_WINDOW_MINUTES));
            orderMapper.insert(order);
            writeStatusLog(order.getId(), null, OrderStatus.CREATED, "USER", userId, "用户提交订单");

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

                    redeemItems.add(RedeemItem.builder()
                            .spuId(sku.getSpuId())
                            .categoryId(sku.getCategoryId())
                            .subtotal(subtotal)
                            .build());

                    OrderItemEntity item = new OrderItemEntity();
                    item.setOrderId(order.getId());
                    item.setOrderNo(order.getOrderNo());
                    item.setSpuId(sku.getSpuId());
                    item.setSkuId(sku.getId());
                    // 类目也进快照：售后政策按类目判定（食品拆封不退），
                    // 而订单行不引用商品表，不存下来就无从判断
                    item.setCategoryId(sku.getCategoryId());
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

                // 优惠券在这里核销：金额要先算出来才知道门槛够不够。
                // 放在扣库存**之后**是有意的 —— 核销需要的「订单金额」此时才确定，
                // 而它失败时下面的 catch 会连同库存一起补偿
                long discount = redeemCoupon(userId, request.getUserCouponId(), order.getOrderNo(), redeemItems);

                order.setTotalAmount(total);
                order.setDiscountAmount(discount);
                order.setPayAmount(total + order.getFreightAmount() - discount);
                orderMapper.updateById(order);
            } catch (RuntimeException e) {
                // **先退券再回补库存**：退券是本地状态改回，代价极小；
                // 而库存在远端，回补失败只能留日志。顺序上先做便宜且可保证的那件
                unrederemCouponQuietly(userId, request.getUserCouponId());
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

    /**
     * 核销优惠券，返回抵扣金额（分）。
     * <p>
     * 没传券就直接返回 0，不走网络。<b>核销失败一律抛异常</b>：用户选了券却没用上，
     * 而订单按原价建出来 —— 那比下单失败糟糕得多，因为用户要等到付款时才发现。
     * <p>
     * 传行明细而不是总额：券服务要按「适用范围内商品小计」判门槛、算折扣，
     * 只给总额的话限类目的券无从校验。
     */
    private long redeemCoupon(String userId, Long userCouponId, String orderNo, List<RedeemItem> items) {
        if (userCouponId == null) {
            return 0L;
        }
        Result<Long> result;
        try {
            result = promotionClient.redeem(userId, RedeemRequest.builder()
                    .userCouponId(userCouponId)
                    .orderNo(orderNo)
                    .items(items)
                    .build());
        } catch (Exception e) {
            log.warn("[Order] 核销优惠券失败 orderNo={} userCouponId={}: {}", orderNo, userCouponId, e.getMessage());
            throw new IllegalStateException("优惠券服务暂时不可用，请稍后再试");
        }
        if (result == null || result.getCode() == null || result.getCode() != 200) {
            throw new IllegalStateException(result == null ? "优惠券核销失败" : result.getMsg());
        }
        return result.getData() == null ? 0L : result.getData();
    }

    /** 退券。失败只记日志 —— 它发生在异常路径上，再抛会把原始异常盖掉 */
    private void unrederemCouponQuietly(String userId, Long userCouponId) {
        if (userCouponId == null) {
            return;
        }
        try {
            promotionClient.unredeem(userId, userCouponId);
        } catch (Exception e) {
            log.error("[Order] 下单失败但优惠券退还失败，需要人工介入 userId={} userCouponId={}: {}",
                    userId, userCouponId, e.getMessage());
        }
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
     * 物流轨迹（用户侧入口）。
     * <p>
     * <b>没有对接任何承运商</b>：真实场景里轨迹由承运商推送，这里只有发货时写入的首条
     * 「已揽收」，之后的节点由管理侧补录。也就是说轨迹是真实落库的数据，
     * 不是按时间推算出来的假节点 —— 编造的物流信息比「暂无轨迹」糟糕得多，
     * 用户会照着它去催件。
     */
    @Override
    public LogisticsResponse getLogistics(String userId, Long orderId) {
        // 先过归属：这一句是用户侧唯一的防线，去掉它这个方法就成了「凭订单号看别人物流」
        getOrder(userId, orderId);
        return logisticsOf(orderId);
    }

    @Override
    public OrderResponse orderById(Long orderId) {
        OrderEntity order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        return toOrderResponse(order);
    }

    @Override
    public LogisticsResponse logisticsOf(Long orderId) {
        OrderEntity order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }

        OrderDeliveryEntity delivery = deliveryMapper.selectOne(
                new LambdaQueryWrapper<OrderDeliveryEntity>()
                        .eq(OrderDeliveryEntity::getOrderId, orderId));
        if (delivery == null) {
            // 未发货就是没有轨迹。**返回空而不是按时间推算出来的假轨迹** ——
            // 编造的物流信息比「暂无轨迹」糟糕得多，用户会照着它去催件
            return LogisticsResponse.builder()
                    .orderId(order.getId())
                    .orderNo(order.getOrderNo())
                    .steps(List.of())
                    .build();
        }

        List<LogisticsStepResponse> steps = deliveryTraceMapper.selectList(
                        new LambdaQueryWrapper<OrderDeliveryTraceEntity>()
                                .eq(OrderDeliveryTraceEntity::getDeliveryId, delivery.getId())
                                .orderByAsc(OrderDeliveryTraceEntity::getHappenAt))
                .stream()
                .map(trace -> LogisticsStepResponse.builder()
                        .status(trace.getStatus())
                        .detail(trace.getDescription())
                        .time(trace.getHappenAt())
                        .build())
                .toList();

        return LogisticsResponse.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .carrier(delivery.getCarrierName())
                .trackingNo(delivery.getTrackingNo())
                .steps(steps)
                .build();
    }

    /**
     * 取消订单。
     * <p>
     * 两条路：未支付 → 直接关闭；已支付未发货 → 关闭并**全额退款**。
     * 已发货之后不从这里走 —— 要退就走进售后，那条路上有政策判定与商家审核。
     */
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
        if (current == OrderStatus.CREATED) {
            return cancelUnpaidOrder(order, userId);
        }
        if (current == OrderStatus.PAID || current == OrderStatus.REFUNDING) {
            return cancelPaidOrder(order, userId);
        }
        if (current.isTerminal()) {
            // text() 自带「已」（已取消/已关闭/已退款），前面不能再拼一个「已」
            throw new IllegalStateException("订单" + current.text() + "，无需重复操作");
        }
        // SHIPPED / RECEIVED / COMPLETED：货已在路上或已签收，取消入口关闭
        throw new IllegalStateException("订单" + current.text() + "，请通过售后申请退款");
    }

    /** 未支付订单取消：不涉及资金，关闭并回补库存、退还优惠券 */
    private OrderResponse cancelUnpaidOrder(OrderEntity order, String userId) {
        OrderStatus current = OrderStatus.parse(order.getStatus());
        // 状态判断下沉到 SQL 的 where 里，由数据库裁决并发，而不是在内存里"读-判断-写"。
        // 纯内存判断挡不住并发：两个取消请求各自读到 CREATED，双双通过守卫，
        // 于是一笔订单回补两次库存 —— 实测 8 个并发取消，库存比正确值多出整整一倍。
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                .eq(OrderEntity::getId, order.getId())
                .eq(OrderEntity::getStatus, current.name())
                .set(OrderEntity::getStatus, OrderStatus.CANCELLED.name())
                .set(OrderEntity::getClosedAt, Times.now()));
        if (updated == 0) {
            throw new IllegalStateException("订单状态刚刚发生变化，请刷新后重试");
        }
        order.setStatus(OrderStatus.CANCELLED.name());
        order.setClosedAt(Times.now());
        writeStatusLog(order.getId(), current, OrderStatus.CANCELLED, "USER", userId, "用户取消订单");

        restoreStockQuietly(order);
        // 券在下单时就核销了，订单既然没成交，券要还给用户 ——
        // 不退的话「用了券又取消」等于券被静默吃掉
        unrederemCouponQuietly(order.getUserId(), order.getUserCouponId());
        log.info("用户 {} 取消订单 {}", userId, order.getOrderNo());
        return toOrderResponse(order);
    }

    /**
     * 已支付未发货订单的取消：全额退款 + 回补库存 + 退券。
     * <p>
     * 也是「退款失败后停在退款中」的<b>重试入口</b>：用户再点一次取消即重试。
     * 重试之所以安全，是因为退款请求带着固定的幂等键（{@code CANCEL:订单号}）——
     * 上次「退成功但响应丢了」时，支付侧按幂等返回原结果，不会退第二笔。
     * <p>
     * <b>退款失败不向上抛</b>：抛出去会把「已置退款中」一起回滚，而退款调用可能已经生效 ——
     * 那才是最难查的一类不一致。失败时留在退款中并留 ERROR，订单列表上如实显示
     * 「退款处理中」，用户可以重试。
     */
    private OrderResponse cancelPaidOrder(OrderEntity order, String userId) {
        // 有售后记录的订单不归这里管：它的退款正门是售后流程。
        // 不挡这一下，一条部分退过款的订单（售后驱动）被取消流程再全额退一次 —— 资损
        Long sales = afterSaleMapper.selectCount(new LambdaQueryWrapper<AfterSaleEntity>()
                .eq(AfterSaleEntity::getOrderId, order.getId()));
        if (sales != null && sales > 0) {
            throw new IllegalStateException("该订单存在售后记录，无法通过取消流程退款");
        }

        OrderStatus current = OrderStatus.parse(order.getStatus());
        boolean retry = current == OrderStatus.REFUNDING;
        if (!retry) {
            int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                    .eq(OrderEntity::getId, order.getId())
                    .eq(OrderEntity::getStatus, current.name())
                    .set(OrderEntity::getStatus, OrderStatus.REFUNDING.name()));
            if (updated == 0) {
                throw new IllegalStateException("订单状态刚刚发生变化，请刷新后重试");
            }
            writeStatusLog(order.getId(), current, OrderStatus.REFUNDING, "USER", userId,
                    "用户取消订单，发起退款");
            order.setStatus(OrderStatus.REFUNDING.name());
        }

        String refundNo;
        try {
            Result<RefundResponse> result = paymentClient.refundForOrder(RefundRequest.builder()
                    .orderId(order.getId())
                    .bizNo("CANCEL:" + order.getOrderNo())
                    .reason("订单取消退款")
                    .build());
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                throw new IllegalStateException(result == null ? "无响应" : result.getMsg());
            }
            refundNo = result.getData() == null ? "" : result.getData().getRefundNo();
        } catch (Exception e) {
            log.error("[Order] 取消订单退款失败，订单停留在退款中，用户可再次取消以重试 orderNo={}: {}",
                    order.getOrderNo(), e.getMessage());
            return toOrderResponse(order);
        }

        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                .eq(OrderEntity::getId, order.getId())
                .eq(OrderEntity::getStatus, OrderStatus.REFUNDING.name())
                .set(OrderEntity::getStatus, OrderStatus.REFUNDED.name())
                .set(OrderEntity::getClosedAt, Times.now()));
        if (updated == 0) {
            // 钱退完了但状态没推动：并发路径。留痕，不抛 —— 事实（钱已退）优先于投影
            log.warn("[Order] 退款已完成但订单状态并发变更 orderNo={} refundNo={}",
                    order.getOrderNo(), refundNo);
            return toOrderResponse(order);
        }
        order.setStatus(OrderStatus.REFUNDED.name());
        order.setClosedAt(Times.now());
        writeStatusLog(order.getId(), OrderStatus.REFUNDING, OrderStatus.REFUNDED, "SYSTEM", null,
                "取消订单退款完成：" + refundNo);

        // 退款已完成，货不可能再发，库存还回去；券同理
        restoreStockQuietly(order);
        unrederemCouponQuietly(order.getUserId(), order.getUserCouponId());
        log.info("用户 {} 取消已支付订单 {}，已全额退款 refundNo={}", userId, order.getOrderNo(), refundNo);
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
            writeStatusLog(order.getId(), OrderStatus.CREATED, OrderStatus.CLOSED,
                    "SYSTEM", null, "超时未支付，自动关闭");
            restoreStockQuietly(order);
            // 券在下单时就核销了 —— 关单同样要把券还回去，否则用户「忘了付款」
            // 的代价是静默丢一张券，而没有任何地方提示过他
            unrederemCouponQuietly(order.getUserId(), order.getUserCouponId());
            closed++;
        }
        if (closed > 0) {
            log.info("[Order] 超时关单 {} 笔，库存与优惠券已归还", closed);
        }
        return closed;
    }

    /**
     * 已收货超过 {@value #AUTO_COMPLETE_DAYS} 天的订单自动完成。
     * <p>
     * 「已完成」原先没有任何写入方：订单走到已收货就停住了，完成状态永远空着。
     * 主动确认收货之外总要有自动的那一半 —— 用户不会专门回来点一个「完成」，
     * 而交易需要一个终点（售后期满、账目结清）。
     * <p>
     * 幂等：状态条件更新让同一条订单被扫到两次时第二次返回 0。
     *
     * @return 本次真正完成的订单数
     */
    @Transactional
    public int completeExpiredReceipts(int batchSize) {
        LocalDateTime deadline = Times.now().minusDays(AUTO_COMPLETE_DAYS);
        List<OrderEntity> due = orderMapper.selectList(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getStatus, OrderStatus.RECEIVED.name())
                .lt(OrderEntity::getReceivedAt, deadline)
                .last("limit " + batchSize));
        int completed = 0;
        for (OrderEntity order : due) {
            int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                    .eq(OrderEntity::getId, order.getId())
                    .eq(OrderEntity::getStatus, OrderStatus.RECEIVED.name())
                    .set(OrderEntity::getStatus, OrderStatus.COMPLETED.name())
                    .set(OrderEntity::getFinishedAt, Times.now()));
            if (updated == 0) {
                continue;
            }
            order.setStatus(OrderStatus.COMPLETED.name());
            writeStatusLog(order.getId(), OrderStatus.RECEIVED, OrderStatus.COMPLETED,
                    "SYSTEM", null, "收货 " + AUTO_COMPLETE_DAYS + " 天期满，交易自动完成");
            completed++;
        }
        if (completed > 0) {
            log.info("[Order] 收货期满自动完成 {} 笔", completed);
        }
        return completed;
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
    public OrderResponse shipOrder(Long orderId, String carrierCode, String carrierName,
                                   String trackingNo, String operatorId) {
        OrderEntity order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        OrderStatus current = OrderStatus.parse(order.getStatus());
        if (current != OrderStatus.PAID) {
            throw new IllegalStateException("只有已支付的订单可以发货，当前状态：" + current.text());
        }

        // 运单号在库上有唯一约束（一个运单号只对应一个包裹）。不先查这一次，
        // 重复的运单号会撞在下面的 insert 上，抛出的数据库异常一路走到兜底处理，
        // 运营看到的是「服务暂时不可用，请稍后再试」——而真实原因是
        // 「这个运单号已经贴在别的订单上了」，那是他自己就能改的。
        // 先查一遍是为了把那句话说清楚；唯一约束仍然是并发下的最终防线
        // （先查后插之间有窗口，那时回滚掉整个事务仍然是正确行为）。
        if (trackingNo != null && deliveryMapper.selectCount(new LambdaQueryWrapper<OrderDeliveryEntity>()
                .eq(OrderDeliveryEntity::getTrackingNo, trackingNo)) > 0) {
            throw new IllegalStateException("运单号 " + trackingNo + " 已经用于其他订单");
        }

        LocalDateTime now = Times.now();
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                .eq(OrderEntity::getId, orderId)
                .eq(OrderEntity::getStatus, current.name())
                .set(OrderEntity::getStatus, OrderStatus.SHIPPED.name())
                .set(OrderEntity::getShippedAt, now));
        if (updated == 0) {
            throw new IllegalStateException("订单状态刚刚发生变化，请刷新后重试");
        }

        OrderDeliveryEntity delivery = new OrderDeliveryEntity();
        delivery.setOrderId(order.getId());
        delivery.setOrderNo(order.getOrderNo());
        delivery.setCarrierCode(carrierCode);
        delivery.setCarrierName(carrierName);
        delivery.setTrackingNo(trackingNo);
        delivery.setStatus("PICKED_UP");
        delivery.setShippedAt(now);
        deliveryMapper.insert(delivery);

        writeTrace(delivery.getId(), "PICKED_UP", "包裹已由承运商揽收", null, now);

        writeStatusLog(order.getId(), current, OrderStatus.SHIPPED, "ADMIN", operatorId,
                "商家发货，运单号 " + trackingNo);
        order.setStatus(OrderStatus.SHIPPED.name());
        order.setShippedAt(now);
        log.info("订单已发货 orderNo={} carrier={} trackingNo={} operator={}",
                order.getOrderNo(), carrierName, trackingNo, operatorId);
        return toOrderResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse confirmReceipt(String userId, Long orderId) {
        OrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getId, orderId)
                .eq(OrderEntity::getUserId, userId));
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        OrderStatus current = OrderStatus.parse(order.getStatus());
        if (current != OrderStatus.SHIPPED) {
            throw new IllegalStateException("只有已发货的订单可以确认收货，当前状态：" + current.text());
        }

        LocalDateTime now = Times.now();
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                .eq(OrderEntity::getId, orderId)
                .eq(OrderEntity::getStatus, current.name())
                .set(OrderEntity::getStatus, OrderStatus.RECEIVED.name())
                .set(OrderEntity::getReceivedAt, now));
        if (updated == 0) {
            throw new IllegalStateException("订单状态刚刚发生变化，请刷新后重试");
        }

        // 履约单同步签收。用条件更新而不是按 orderId 全量改：
        // 同一订单只该有一条履约单，但万一日后支持拆单，这里也不会误伤别的包裹
        deliveryMapper.update(null, new LambdaUpdateWrapper<OrderDeliveryEntity>()
                .eq(OrderDeliveryEntity::getOrderId, orderId)
                .set(OrderDeliveryEntity::getStatus, "SIGNED")
                .set(OrderDeliveryEntity::getSignedAt, now));

        OrderDeliveryEntity delivery = deliveryMapper.selectOne(
                new LambdaQueryWrapper<OrderDeliveryEntity>().eq(OrderDeliveryEntity::getOrderId, orderId));
        if (delivery != null) {
            writeTrace(delivery.getId(), "SIGNED", "包裹已签收", null, now);
        }

        writeStatusLog(order.getId(), current, OrderStatus.RECEIVED, "USER", userId, "用户确认收货");
        order.setStatus(OrderStatus.RECEIVED.name());
        order.setReceivedAt(now);
        log.info("订单已确认收货 orderNo={}", order.getOrderNo());
        return toOrderResponse(order);
    }

    /**
     * 写一条状态流水。**每一次状态变更都要调它** ——
     * 建了表却没有写入代码，等于没有流水；而用户问「为什么是这个状态」时，
     * 唯一的答案就在这里。
     */
    private void writeStatusLog(Long orderId, OrderStatus from, OrderStatus to,
                                String operatorType, String operatorId, String remark) {
        OrderStatusLogEntity log = new OrderStatusLogEntity();
        log.setOrderId(orderId);
        log.setFromStatus(from == null ? null : from.name());
        log.setToStatus(to.name());
        log.setOperatorType(operatorType);
        log.setOperatorId(operatorId);
        log.setRemark(remark);
        log.setCreatedAt(Times.now());
        orderStatusLogMapper.insert(log);
    }

    private void writeTrace(Long deliveryId, String status, String description,
                            String location, LocalDateTime happenAt) {
        OrderDeliveryTraceEntity trace = new OrderDeliveryTraceEntity();
        trace.setDeliveryId(deliveryId);
        trace.setHappenAt(happenAt);
        trace.setStatus(status);
        trace.setDescription(description);
        trace.setLocation(location);
        deliveryTraceMapper.insert(trace);
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
            // 钱收了、单却关了（超时关单或用户取消），**自动发起全额退款**。
            // 之前这里只写一行 log.error("需要人工退款")，而退款入口全仓库零调用方 ——
            // 于是「自动退款」这件事从来没有任何人做过，用户的钱只能靠人翻日志找回来
            refundPaidButClosedOrder(order, current);
            return;
        }
        // 用状态机判定，而不是「非终态就放行」。后者会让一条迟到/重复的支付事件
        // 把已发货的订单改回待发货 —— 而 ALLOWED 里 SHIPPED 只允许去 RECEIVED/REFUNDING
        if (!current.canTransitTo(OrderStatus.PAID)) {
            log.error("[Order] 订单当前状态「{}」不允许流转到已支付，拒绝 orderId={} orderNo={}",
                    current.text(), orderId, order.getOrderNo());
            return;
        }

        LocalDateTime now = Times.now();
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                .eq(OrderEntity::getId, orderId)
                .eq(OrderEntity::getStatus, current.name())
                .set(OrderEntity::getStatus, OrderStatus.PAID.name())
                .set(OrderEntity::getPaidAt, now));
        if (updated == 0) {
            log.warn("[Order] 订单状态并发变更，支付完成事件未生效 orderId={}", orderId);
            return;
        }
        writeStatusLog(orderId, current, OrderStatus.PAID, "SYSTEM", null, "支付完成");
        order.setStatus(OrderStatus.PAID.name());
        order.setPaidAt(now);
        log.info("[Order] 订单已标记为已支付 orderId={} orderNo={}", orderId, order.getOrderNo());
    }

    /**
     * 已关闭的订单收到了支付 —— 自动退款。
     * <p>
     * 这是「超时关单」与「迟到支付」撞在一起的必然结果：用户在窗口末尾建了支付单，
     * 关单任务把订单关掉并回补了库存，随后渠道的成功回调才到。
     * <p>
     * <b>失败要抛出去</b>：这条路径由 MQ 消费触发，抛异常会走重试、最终进死信队列，
     * 人能捞得回来。只记日志等于把「用户的钱没退」这件事埋进日志里。
     */
    private void refundPaidButClosedOrder(OrderEntity order, OrderStatus current) {
        try {
            Result<RefundResponse> result = paymentClient.refundForOrder(RefundRequest.builder()
                    .orderId(order.getId())
                    // 幂等键固定：这条路径由 MQ 消费触发，重试是常态 ——
                    // 键不变时「上次退成功但响应丢了」的重试会按幂等返回原结果，
                    // 而不是撞在额度校验上抛异常、最终进死信
                    .bizNo("CANCEL:" + order.getOrderNo())
                    // 金额留空 = 全额退。订单已关闭，没有任何部分退的理由
                    .reason("订单" + current.text() + "，支付结果迟到，自动全额退款")
                    .build());
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                throw new IllegalStateException(result == null ? "无响应" : result.getMsg());
            }
            log.warn("[Order] 订单{}却收到支付，已自动全额退款 orderNo={} refundNo={}",
                    current.text(), order.getOrderNo(),
                    result.getData() == null ? "" : result.getData().getRefundNo());
        } catch (Exception e) {
            log.error("[Order] 已关闭订单收到支付且自动退款失败，需要人工介入 orderNo={} orderId={}: {}",
                    order.getOrderNo(), order.getId(), e.getMessage());
            // 抛出去让 MQ 重试；重试耗尽会进死信队列，那是人能接手的地方
            throw new IllegalStateException("自动退款失败，订单号 " + order.getOrderNo(), e);
        }
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
                .statusText(status.text())
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
}
