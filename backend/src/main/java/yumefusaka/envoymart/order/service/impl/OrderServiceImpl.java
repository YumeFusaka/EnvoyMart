package yumefusaka.envoymart.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.cart.model.entity.CartItemEntity;
import yumefusaka.envoymart.cart.service.CartService;
import yumefusaka.envoymart.order.mapper.OrderItemMapper;
import yumefusaka.envoymart.order.mapper.OrderMapper;
import yumefusaka.envoymart.order.model.entity.OrderEntity;
import yumefusaka.envoymart.order.model.entity.OrderItemEntity;
import yumefusaka.envoymart.order.model.request.CheckoutRequest;
import yumefusaka.envoymart.order.model.response.LogisticsResponse;
import yumefusaka.envoymart.order.model.response.LogisticsStepResponse;
import yumefusaka.envoymart.order.model.response.OrderItemResponse;
import yumefusaka.envoymart.order.model.response.OrderResponse;
import yumefusaka.envoymart.order.service.OrderService;
import yumefusaka.envoymart.product.model.entity.ProductEntity;
import yumefusaka.envoymart.product.service.ProductService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final CartService cartService;
    private final ProductService productService;

    public OrderServiceImpl(OrderMapper orderMapper,
                            OrderItemMapper orderItemMapper,
                            CartService cartService,
                            ProductService productService) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.cartService = cartService;
        this.productService = productService;
    }

    @Override
    @Transactional
    public OrderResponse checkout(String userId, CheckoutRequest request) {
        List<CartItemEntity> cartItems = cartService.listEntities(userId);
        if (cartItems.isEmpty()) {
            throw new IllegalArgumentException("购物车为空，无法下单");
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        OrderEntity order = new OrderEntity();
        order.setOrderNo("YS"
                + DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(LocalDateTime.now())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
        order.setUserId(userId);
        order.setRecipientName(request.getRecipientName());
        order.setRecipientPhone(request.getRecipientPhone());
        order.setAddress(request.getAddress());
        order.setStatus("DELIVERING");
        order.setCreatedAt(LocalDateTime.now());
        order.setTotalAmount(totalAmount);
        orderMapper.insert(order);

        for (CartItemEntity cartItem : cartItems) {
            ProductEntity product = productService.requireEntity(cartItem.getProductId());
            productService.decreaseStock(product.getId(), cartItem.getQuantity());
            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity()));
            totalAmount = totalAmount.add(subtotal);

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

        order.setTotalAmount(totalAmount);
        orderMapper.updateById(order);
        cartService.clearUserCart(userId);
        return getOrder(userId, order.getId());
    }

    @Override
    public List<OrderResponse> listOrders(String userId) {
        List<OrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getUserId, userId)
                .orderByDesc(OrderEntity::getCreatedAt));
        return orders.stream().map(this::toResponse).toList();
    }

    @Override
    public OrderResponse getOrder(String userId, Long orderId) {
        OrderEntity order = requireOrder(userId, orderId);
        return toResponse(order);
    }

    @Override
    public LogisticsResponse getLogistics(String userId, Long orderId) {
        OrderEntity order = requireOrder(userId, orderId);
        LocalDateTime createdAt = order.getCreatedAt();
        return LogisticsResponse.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .carrier("Yume Express")
                .trackingNo("YE" + order.getOrderNo().substring(2))
                .steps(List.of(
                        LogisticsStepResponse.builder().status("已下单").detail("订单已创建，等待仓库拣货").time(createdAt).build(),
                        LogisticsStepResponse.builder().status("已出库").detail("包裹已完成打包并离开仓库").time(createdAt.plusHours(4)).build(),
                        LogisticsStepResponse.builder().status("运输中").detail("包裹正在前往目的城市分拨中心").time(createdAt.plusHours(18)).build(),
                        LogisticsStepResponse.builder().status("派送中").detail("快递员正在派送，请保持电话畅通").time(createdAt.plusDays(1)).build()
                ))
                .build();
    }

    @Override
    public OrderResponse getLatestOrder(String userId) {
        OrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getUserId, userId)
                .orderByDesc(OrderEntity::getCreatedAt)
                .last("limit 1"));
        if (order == null) {
            throw new IllegalArgumentException("当前暂无订单");
        }
        return toResponse(order);
    }

    private OrderEntity requireOrder(String userId, Long orderId) {
        OrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getId, orderId)
                .eq(OrderEntity::getUserId, userId));
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        return order;
    }

    private OrderResponse toResponse(OrderEntity order) {
        List<OrderItemEntity> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItemEntity>()
                .eq(OrderItemEntity::getOrderId, order.getId()));
        return OrderResponse.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .recipientName(order.getRecipientName())
                .recipientPhone(order.getRecipientPhone())
                .address(order.getAddress())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .items(items.stream().map(item -> OrderItemResponse.builder()
                        .id(item.getId())
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .productImage(item.getProductImage())
                        .unitPrice(item.getUnitPrice())
                        .quantity(item.getQuantity())
                        .subtotal(item.getSubtotal())
                        .build()).toList())
                .build();
    }
}
