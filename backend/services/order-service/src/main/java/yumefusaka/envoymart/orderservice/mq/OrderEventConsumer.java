package yumefusaka.envoymart.orderservice.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 订单事件消费者：异步处理订单相关事件
 */
@Slf4j
@Component
public class OrderEventConsumer {

    @RabbitListener(queues = OrderEventConfig.ORDER_CREATED_QUEUE)
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("[MQ.Consumer] 收到订单创建事件: orderNo={}, 异步处理库存预扣、通知等", event.getOrderNo());
    }

    @RabbitListener(queues = OrderEventConfig.PAYMENT_COMPLETED_QUEUE)
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("[MQ.Consumer] 收到支付完成事件: orderNo={}, txNo={}, 异步更新订单状态",
                event.getOrderNo(), event.getTransactionNo());
    }

    @RabbitListener(queues = OrderEventConfig.STOCK_UPDATED_QUEUE)
    public void handleStockUpdated(StockUpdatedEvent event) {
        log.info("[MQ.Consumer] 收到库存变更事件: productId={}, 剩余库存={}",
                event.getProductId(), event.getRemainingStock());
    }

    @RabbitListener(queues = OrderEventConfig.ORDER_DLX_QUEUE)
    public void handleDeadLetter(String message) {
        log.warn("[MQ.DLX] 收到死信消息: {}, 进入人工处理流程", message);
    }
}
