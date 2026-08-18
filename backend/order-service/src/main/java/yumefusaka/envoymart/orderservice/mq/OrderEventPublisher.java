package yumefusaka.envoymart.orderservice.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 订单事件发布器：发送事件到 RabbitMQ
 */
@Slf4j
@Component
public class OrderEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public OrderEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishOrderCreated(OrderCreatedEvent event) {
        rabbitTemplate.convertAndSend(
                OrderEventConfig.ORDER_EXCHANGE,
                OrderEventConfig.ORDER_CREATED_KEY,
                event);
        log.info("[MQ] 订单创建事件已发布: orderNo={}, amount={}", event.getOrderNo(), event.getTotalAmount());
    }

    public void publishPaymentCompleted(PaymentCompletedEvent event) {
        rabbitTemplate.convertAndSend(
                OrderEventConfig.ORDER_EXCHANGE,
                OrderEventConfig.PAYMENT_COMPLETED_KEY,
                event);
        log.info("[MQ] 支付完成事件已发布: orderNo={}, transactionNo={}", event.getOrderNo(), event.getTransactionNo());
    }

    public void publishStockUpdated(StockUpdatedEvent event) {
        rabbitTemplate.convertAndSend(
                OrderEventConfig.ORDER_EXCHANGE,
                OrderEventConfig.STOCK_UPDATED_KEY,
                event);
        log.info("[MQ] 库存更新事件已发布: productId={}, deducted={}", event.getProductId(), event.getDeductedQuantity());
    }
}
