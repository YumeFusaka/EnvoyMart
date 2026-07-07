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
        // ponytail: 实际在此处调用库存服务确认预扣、发送通知等
    }

    @RabbitListener(queues = OrderEventConfig.PAYMENT_COMPLETED_QUEUE)
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("[MQ.Consumer] 收到支付完成事件: orderNo={}, txNo={}, 异步更新订单状态",
                event.getOrderNo(), event.getTransactionNo());
        // ponytail: 实际在此处更新订单状态为"已支付"，发送发货通知
    }

    @RabbitListener(queues = OrderEventConfig.STOCK_UPDATED_QUEUE)
    public void handleStockUpdated(StockUpdatedEvent event) {
        log.info("[MQ.Consumer] 收到库存变更事件: productId={}, 剩余库存={}",
                event.getProductId(), event.getRemainingStock());
        // ponytail: 实际在此处同步缓存或触发补货预警
    }

    @RabbitListener(queues = OrderEventConfig.ORDER_DLX_QUEUE)
    public void handleDeadLetter(String message) {
        log.warn("[MQ.DLX] 收到死信消息: {}, 进入人工处理流程", message);
        // ponytail: 实际在此处记录失败事件并告警
    }
}
