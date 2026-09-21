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
import yumefusaka.envoymart.orderservice.client.ProductClient;
import yumefusaka.envoymart.orderservice.config.SentinelDegradeConfig;
import yumefusaka.envoymart.orderservice.entity.CartItemEntity;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;
import yumefusaka.envoymart.orderservice.entity.OrderItemEntity;
import yumefusaka.envoymart.orderservice.mapper.CartItemMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderItemMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderMapper;
import yumefusaka.envoymart.orderservice.model.AddCartItemRequest;
import yumefusaka.envoymart.orderservice.model.CartItemResponse;
import yumefusaka.envoymart.orderservice.model.CheckoutRequest;
import yumefusaka.envoymart.orderservice.model.LogisticsResponse;
import yumefusaka.envoymart.orderservice.model.LogisticsStepResponse;
import yumefusaka.envoymart.orderservice.model.OrderItemResponse;
import yumefusaka.envoymart.orderservice.model.OrderResponse;
import yumefusaka.envoymart.orderservice.model.ProductSnapshot;
import yumefusaka.envoymart.orderservice.model.StockDeductRequest;
import yumefusaka.envoymart.orderservice.model.UpdateCartItemRequest;
import yumefusaka.envoymart.orderservice.mq.OrderCreatedEvent;
import yumefusaka.envoymart.orderservice.mq.OrderEventPublisher;
import yumefusaka.envoymart.orderservice.mq.OrderItemEvent;
import yumefusaka.envoymart.orderservice.service.OrderDomainService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class OrderDomainServiceImpl implements OrderDomainService {

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
    @Transactional
    public CartItemResponse addCartItem(String userId, AddCartItemRequest request) {
        ProductSnapshot product = requireProduct(request.getProductId());
        CartItemEntity entity = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .eq(CartItemEntity::getProductId, request.getProductId()));
        if (entity == null) {
            entity = new CartItemEntity();
            entity.setUserId(userId);
            entity.setProductId(request.getProductId());
            entity.setQuantity(request.getQuantity());
            cartItemMapper.insert(entity);
        } else {
            entity.setQuantity(entity.getQuantity() + request.getQuantity());
            cartItemMapper.updateById(entity);
        }
        // 加购同样改了购物车，必须与 updateCartItem / checkout 一样失效缓存，
        // 否则最长到 TTL 结束前，用户看到的都是加购前的那份
        cartCacheService.evictCartCache(userId);
        return toCartResponse(entity, product);
    }

    @Override
    public List<CartItemResponse> listCartItems(String userId) {
        // 优先从缓存读取，预热后减少 DB 查询
        List<CartItemResponse> cached = cartCacheService.getCachedCart(userId);
        if (!cached.isEmpty()) {
            return cached;
        }
        List<CartItemResponse> items = cartItemMapper.selectList(
                        new LambdaQueryWrapper<CartItemEntity>().eq(CartItemEntity::getUserId, userId))
                .stream()
                .map(item -> toCartResponse(item, requireProduct(item.getProductId())))
                .toList();
        cartCacheService.cacheCart(userId, items);
        return items;
    }

    @Override
    public CartItemResponse updateCartItem(String userId, Long id, UpdateCartItemRequest request) {
        CartItemEntity entity = requireCartItem(userId, id);
        entity.setQuantity(request.getQuantity());
        cartItemMapper.updateById(entity);
        cartCacheService.evictCartCache(userId);
        return toCartResponse(entity, requireProduct(entity.getProductId()));
    }

    @Override
    @GlobalTransactional(name = "envoymart-checkout", rollbackFor = Exception.class)
    @Transactional
    public OrderResponse checkout(String userId, CheckoutRequest request) {
        List<CartItemEntity> cartItems = cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId));
        if (cartItems.isEmpty()) {
            throw new IllegalArgumentException("购物车为空，无法下单");
        }

        // 加锁范围必须与释放范围一致。
        // 若只把"用锁"的那段包进 try，加锁过程中途失败（并发抢锁超时、Feign 报错、线程中断）
        // 会让已经拿到的锁一直不释放，只能等租期自然过期，期间同商品的其他用户全部下单失败。
        List<Long> lockedProductIds = new ArrayList<>();
        OrderEntity order = null;
        // 声明在 try 之外：事件发布已经挪到锁外，这几个值要带出去
        BigDecimal total = BigDecimal.ZERO;
        // 事件载荷要的商品信息，**在下面的扣减循环里已经查过一次了**，顺手收起来即可。
        // 原先为了拼事件又对每件商品调了一次 requireProduct——那是凭空多出来的 N 次
        // 跨服务调用，而且全部发生在库存锁里。
        List<OrderItemEvent> eventItems = new ArrayList<>();
        try {
            for (CartItemEntity cartItem : cartItems) {
                if (!cartCacheService.tryLock(cartItem.getProductId())) {
                    ProductSnapshot p = requireProduct(cartItem.getProductId());
                    throw new IllegalStateException("商品「" + p.getName() + "」当前购买人数过多，请稍后再试");
                }
                // 拿锁成功才记账：失败的那把本就没拿到，不需要（也不能）释放
                lockedProductIds.add(cartItem.getProductId());
            }

            // 先锁后重读：加锁前读到的是陈旧快照，并发的另一次下单可能已经清空了购物车。
            // 不重读的话锁形同虚设——拿着旧快照继续扣一次库存、再建一张单。
            cartItems = cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                    .eq(CartItemEntity::getUserId, userId));
            if (cartItems.isEmpty()) {
                throw new IllegalArgumentException("购物车为空，无法下单");
            }

            order = new OrderEntity();
            order.setOrderNo("YS" + DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(LocalDateTime.now())
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
            order.setUserId(userId);
            order.setRecipientName(request.getRecipientName());
            order.setRecipientPhone(request.getRecipientPhone());
            order.setAddress(request.getAddress());
            order.setStatus("DELIVERING");
            order.setCreatedAt(LocalDateTime.now());
            order.setTotalAmount(BigDecimal.ZERO);
            orderMapper.insert(order);

            // 记账本：已经成功扣掉的库存，失败时按相反顺序还回去。
            // 跨服务调用不参与本地事务——product-service 有自己的库和事务，order-service
            // 回滚不会撤销它已提交的扣减，也没有任何补偿。实测购物车里有 2 件商品、
            // 排在后面的那件库存不足时，第一件的库存已经被扣且无人归还：本地事务回滚了，
            // 订单没建、购物车没清，库存却实打实少了一份。反复触发可以在"零订单"的
            // 情况下把整仓库存刷空。
            List<StockDeductRequest> deducted = new ArrayList<>();
            total = BigDecimal.ZERO;
            try {
                for (CartItemEntity cartItem : cartItems) {
                    ProductSnapshot product = requireProduct(cartItem.getProductId());
                    // 必须检查返回的业务码：product-service 的异常被统一包成 HTTP 200 + code=500，
                    // **Feign 只按状态码判断成败，不会抛异常**。直接丢弃返回值等于把扣减失败当成功，
                    // 订单照建、库存不扣——而且整条链路不会报任何错。
                    deductStockWithCircuitBreaker(product.getId(), cartItem.getQuantity(), product.getName());
                    deducted.add(new StockDeductRequest(product.getId(), cartItem.getQuantity()));
                    BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity()));
                    total = total.add(subtotal);
                    OrderItemEntity item = new OrderItemEntity();
                    item.setOrderId(order.getId());
                    item.setProductId(product.getId());
                    item.setProductName(product.getName());
                    item.setProductImage(product.getImage());
                    item.setUnitPrice(product.getPrice());
                    item.setQuantity(cartItem.getQuantity());
                    item.setSubtotal(subtotal);
                    orderItemMapper.insert(item);
                    eventItems.add(OrderItemEvent.builder()
                            .productId(product.getId())
                            .productName(product.getName())
                            .quantity(cartItem.getQuantity())
                            .price(product.getPrice())
                            .build());
                }
                order.setTotalAmount(total);
                orderMapper.updateById(order);
            } catch (RuntimeException e) {
                compensateStock(deducted);
                throw e;
            }
            cartItemMapper.delete(new LambdaQueryWrapper<CartItemEntity>().eq(CartItemEntity::getUserId, userId));
            cartCacheService.evictCartCache(userId);  // 清除购物车缓存
            log.info("用户 {} 下单成功，订单号 {}", userId, order.getOrderNo());
        } finally {
            // 逆序释放，与加锁顺序相反，降低与其他事务交叉持锁时死锁的概率
            for (int i = lockedProductIds.size() - 1; i >= 0; i--) {
                cartCacheService.unlock(lockedProductIds.get(i));
            }
        }

        // 事件发布挪到**锁外**。
        //
        // 它是"可以重来的副作用"，没有理由占着库存锁：一次 MQ 发布是一次网络往返，
        // 留在锁里就直接计入临界区时长——而「临界区时长 × 并发数」正是队尾请求要等的时间，
        // 这条线实测过（30 并发下单时它是决定成败的那个量）。
        publishOrderCreatedQuietly(order, userId, total, eventItems);

        return getOrder(userId, order.getId());
    }

    /**
     * 发布订单创建事件，失败只记日志。
     * <p>
     * **不能让它影响主流程**：订单已经建好，本地事务也已经提交，事件丢了只该留一条 ERROR。
     * 这个异常如果抛出去，本地事务会回滚、订单不建，**而 product-service 那边扣掉的库存
     * 已经提交、没有任何人回补**——实测并发下单时整仓库存被这样刷空过。
     * <p>
     * 调用点刻意放在加锁的 try/finally 之外，所以这里不需要考虑锁的释放。
     */
    private void publishOrderCreatedQuietly(OrderEntity order, String userId,
                                            BigDecimal total, List<OrderItemEvent> items) {
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

    @Override
    public LogisticsResponse getLogistics(String userId, Long orderId) {
        OrderResponse order = getOrder(userId, orderId);
        LocalDateTime createdAt = order.getCreatedAt();
        return LogisticsResponse.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .carrier("Yume Express")
                .trackingNo("YE" + order.getOrderNo().substring(2, 12))
                .steps(List.of(
                        LogisticsStepResponse.builder().status("已下单").detail("订单已创建，等待仓库拣货").time(createdAt).build(),
                        LogisticsStepResponse.builder().status("已出库").detail("包裹已完成打包并离开仓库").time(createdAt.plusHours(4)).build(),
                        LogisticsStepResponse.builder().status("运输中").detail("包裹正在前往目的城市分拨中心").time(createdAt.plusHours(18)).build(),
                        LogisticsStepResponse.builder().status("派送中").detail("快递员正在派送，请保持电话畅通").time(createdAt.plusDays(1)).build()
                ))
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
        if ("CANCELLED".equals(order.getStatus())) {
            throw new IllegalStateException("订单已取消，无需重复操作");
        }
        if ("PAID".equals(order.getStatus())) {
            throw new IllegalStateException("已支付订单请走退款流程");
        }

        // 状态判断下沉到 SQL 的 where 里，由数据库裁决并发，而不是在内存里"读-判断-写"。
        // 纯内存判断挡不住并发：两个取消请求各自读到 DELIVERING，双双通过上面的守卫，
        // 于是一笔订单回补两次库存——实测 8 个并发取消，库存比正确值多出整整一倍。
        // 条件更新只有一个能命中，其余 updated=0，据此拒绝，回补也就只发生一次。
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                .eq(OrderEntity::getId, orderId)
                .eq(OrderEntity::getStatus, order.getStatus())
                .set(OrderEntity::getStatus, "CANCELLED"));
        if (updated == 0) {
            throw new IllegalStateException("订单状态刚刚发生变化，请刷新后重试");
        }
        order.setStatus("CANCELLED");

        // 回补库存，避免取消后商品被"锁死"
        List<OrderItemEntity> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItemEntity>().eq(OrderItemEntity::getOrderId, orderId));
        for (OrderItemEntity item : items) {
            try {
                requireSuccess(productClient.restoreStock(
                        new StockDeductRequest(item.getProductId(), item.getQuantity())),
                        "回补库存 productId=" + item.getProductId());
            } catch (Exception e) {
                // 不能因为回补失败就回滚整个取消：多件商品时前面的可能已经回补成功，
                // 回滚只会把订单状态退回去而库存已经还了，变成"库存多出来"。
                // 但必须以 ERROR 留痕——静默吞掉会让库存越差越多且无人察觉。
                // 已知的最终一致性问题：这里没有重试也没有对账任务，product-service
                // 长时间不可用时需要人工把库存补回。
                log.error("回补库存失败，订单已取消但库存未归还 orderId={} productId={} quantity={}: {}",
                        orderId, item.getProductId(), item.getQuantity(), e.getMessage());
            }
        }

        log.info("用户 {} 取消订单 {}", userId, order.getOrderNo());
        return toOrderResponse(order);
    }

    @Override
    @Transactional
    public void markPaid(Long orderId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            log.error("[Order] 收到支付完成事件但订单不存在 orderId={}", orderId);
            return;
        }
        if ("PAID".equals(order.getStatus())) {
            return;  // 重复投递，幂等吞掉
        }
        if ("CANCELLED".equals(order.getStatus())) {
            // 钱收了、单却取消了——这是资金问题，留明确记录等人工退款，
            // 不能自动改成 PAID 把矛盾掩盖过去
            log.error("[Order] 订单已取消却收到支付完成事件，需要人工退款 orderId={} orderNo={}",
                    orderId, order.getOrderNo());
            return;
        }
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                .eq(OrderEntity::getId, orderId)
                .eq(OrderEntity::getStatus, order.getStatus())
                .set(OrderEntity::getStatus, "PAID"));
        if (updated == 0) {
            log.warn("[Order] 订单状态并发变更，支付完成事件未生效 orderId={}", orderId);
            return;
        }
        log.info("[Order] 订单已标记为已支付 orderId={} orderNo={}", orderId, order.getOrderNo());
    }

    /**
     * 反序归还已扣减的库存。
     * <p>
     * 反序是为了与扣减顺序相反，和加解锁的约定一致，降低与其他事务交叉时的死锁概率。
     * 补偿本身再失败就只能留 ERROR：远端已经提交，本地既无法回滚也没有重试机制，
     * 属于需要人工介入的最终一致性问题——宁可吵，不可静默。
     */
    private void compensateStock(List<StockDeductRequest> deducted) {
        for (int i = deducted.size() - 1; i >= 0; i--) {
            StockDeductRequest request = deducted.get(i);
            try {
                requireSuccess(productClient.restoreStock(request),
                        "补偿回补库存 productId=" + request.getProductId());
                log.warn("[Order] 下单失败，已回补库存 productId={} quantity={}",
                        request.getProductId(), request.getQuantity());
            } catch (Exception e) {
                log.error("[Order] 下单失败且库存补偿失败，需要人工处理 productId={} quantity={}: {}",
                        request.getProductId(), request.getQuantity(), e.getMessage());
            }
        }
    }

    private ProductSnapshot requireProduct(Long productId) {
        ProductSnapshot product = productClient.getProduct(productId).getData();
        if (product == null) {
            throw new IllegalArgumentException("商品不存在");
        }
        return product;
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
     * <b>熔断与超时解决的不是一回事</b>：原先只有超时（read 5s），下游挂掉后每个请求
     * 仍要干等 5 秒才失败——并发一上来，调用方线程先被占满，故障从下游蔓延到上游。
     * 熔断打开后直接拒绝，不占用等待时间。
     * <p>
     * <b>熔断后刻意不降级为"成功"</b>：库存扣减没有这个选项，扣不了就是不能下单。
     * 这里抛业务异常快速失败，与"库存不足"一样让本次下单失败，只是错误信息不同——
     * 前者是下游暂时不可用（可重试），后者是库存真的不够（重试无用）。
     */
    private void deductStockWithCircuitBreaker(Long productId, Integer quantity, String productName) {
        Entry entry = null;
        try {
            entry = SphU.entry(SentinelDegradeConfig.RESOURCE_DEDUCT_STOCK);
            // 必须检查返回的业务码：product-service 的异常被统一包成 HTTP 200 + code=500，
            // **Feign 只按状态码判断成败，不会抛异常**。直接丢弃返回值等于把扣减失败当成功，
            // 订单照建、库存不扣——而且整条链路不会报任何错。
            requireSuccess(productClient.deductStock(new StockDeductRequest(productId, quantity)),
                    "扣减库存 " + productName);
        } catch (BlockException e) {
            log.warn("[Sentinel] 扣减库存被熔断: productId={}", productId);
            throw new IllegalStateException("库存服务暂时不可用，请稍后重试");
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    private CartItemEntity requireCartItem(String userId, Long id) {
        CartItemEntity entity = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .eq(CartItemEntity::getId, id));
        if (entity == null) {
            throw new IllegalArgumentException("购物车条目不存在");
        }
        return entity;
    }

    private CartItemResponse toCartResponse(CartItemEntity entity, ProductSnapshot product) {
        return CartItemResponse.builder()
                .id(entity.getId())
                .productId(product.getId())
                .name(product.getName())
                .image(product.getImage())
                .price(product.getPrice())
                .quantity(entity.getQuantity())
                .stock(product.getStock())
                .subtotal(product.getPrice().multiply(BigDecimal.valueOf(entity.getQuantity())))
                .build();
    }

    private OrderResponse toOrderResponse(OrderEntity order) {
        List<OrderItemResponse> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItemEntity>()
                        .eq(OrderItemEntity::getOrderId, order.getId()))
                .stream()
                .map(item -> OrderItemResponse.builder()
                        .id(item.getId())
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .productImage(item.getProductImage())
                        .unitPrice(item.getUnitPrice())
                        .quantity(item.getQuantity())
                        .subtotal(item.getSubtotal())
                        .build())
                .toList();
        return OrderResponse.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .recipientName(order.getRecipientName())
                .recipientPhone(order.getRecipientPhone())
                .address(order.getAddress())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .items(items)
                .build();
    }
}
