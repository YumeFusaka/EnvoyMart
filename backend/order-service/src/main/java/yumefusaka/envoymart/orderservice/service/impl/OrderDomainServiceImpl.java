package yumefusaka.envoymart.orderservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.orderservice.client.ProductClient;
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
import yumefusaka.envoymart.orderservice.mq.StockUpdatedEvent;
import yumefusaka.envoymart.orderservice.service.OrderDomainService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
    @Transactional
    public OrderResponse checkout(String userId, CheckoutRequest request) {
        List<CartItemEntity> cartItems = cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId));
        if (cartItems.isEmpty()) {
            throw new IllegalArgumentException("购物车为空，无法下单");
        }

        // 逐商品加分布式锁，防止超卖
        for (CartItemEntity cartItem : cartItems) {
            RLock lock = cartCacheService.getStockLock(cartItem.getProductId());
            try {
                if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                    ProductSnapshot p = requireProduct(cartItem.getProductId());
                    throw new IllegalStateException("商品「" + p.getName() + "」当前购买人数过多，请稍后再试");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("获取锁被中断", e);
            }
        }

        OrderEntity order = new OrderEntity();
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

        BigDecimal total = BigDecimal.ZERO;
        try {
            for (CartItemEntity cartItem : cartItems) {
                ProductSnapshot product = requireProduct(cartItem.getProductId());
                productClient.deductStock(new StockDeductRequest(product.getId(), cartItem.getQuantity()));
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
            }
            order.setTotalAmount(total);
            orderMapper.updateById(order);
            cartItemMapper.delete(new LambdaQueryWrapper<CartItemEntity>().eq(CartItemEntity::getUserId, userId));
            cartCacheService.evictCartCache(userId);  // 清除购物车缓存
            // 发布订单创建事件（异步解耦后续流程）
            eventPublisher.publishOrderCreated(OrderCreatedEvent.builder()
                    .orderId(order.getId())
                    .orderNo(order.getOrderNo())
                    .userId(userId)
                    .totalAmount(total)
                    .items(cartItems.stream().map(ci -> {
                        ProductSnapshot p = requireProduct(ci.getProductId());
                        return OrderItemEvent.builder()
                                .productId(p.getId())
                                .productName(p.getName())
                                .quantity(ci.getQuantity())
                                .price(p.getPrice())
                                .build();
                    }).toList())
                    .createdAt(order.getCreatedAt())
                    .build());
            log.info("用户 {} 下单成功，订单号 {}", userId, order.getOrderNo());
        } finally {
            // 释放所有分布式锁
            cartItems.forEach(item -> cartCacheService.unlock(item.getProductId()));
        }
        return getOrder(userId, order.getId());
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

    private ProductSnapshot requireProduct(Long productId) {
        ProductSnapshot product = productClient.getProduct(productId).getData();
        if (product == null) {
            throw new IllegalArgumentException("商品不存在");
        }
        return product;
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
