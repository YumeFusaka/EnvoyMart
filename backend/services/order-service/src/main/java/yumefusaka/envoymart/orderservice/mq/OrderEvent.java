package yumefusaka.envoymart.orderservice.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单创建事件（当用户下单成功时发布）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {
    private Long orderId;
    private String orderNo;
    private String userId;
    private BigDecimal totalAmount;
    private List<OrderItemEvent> items;
    private LocalDateTime createdAt;
}

/**
 * 支付完成事件（当支付成功时发布）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCompletedEvent {
    private Long orderId;
    private String orderNo;
    private String transactionNo;
    private BigDecimal amount;
    private LocalDateTime paidAt;
}

/**
 * 库存扣减事件（当库存变化时发布）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockUpdatedEvent {
    private Long productId;
    private Integer deductedQuantity;
    private Integer remainingStock;
}

/**
 * 订单商品明细事件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemEvent {
    private Long productId;
    private String productName;
    private Integer quantity;
    private BigDecimal price;
}
