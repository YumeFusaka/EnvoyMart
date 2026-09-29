package yumefusaka.envoymart.orderservice.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** 订单创建事件（用户下单成功时发布） */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {

    private Long orderId;
    private String orderNo;
    private String userId;
    /** 单位「分」，与订单表一致 */
    private Long totalAmount;
    private List<OrderItemEvent> items;
    private LocalDateTime createdAt;
}
