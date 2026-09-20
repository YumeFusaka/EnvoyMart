package yumefusaka.envoymart.orderservice.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 订单事件发布器：发送事件到 RabbitMQ。
 * <p>
 * 只保留真正在发的事件。原先这里还有 {@code publishStockUpdated} 与
 * {@code publishPaymentCompleted}，两者都没有调用方——前者让整条 stock.updated
 * 队列/绑定/消费者一起悬空（队列建了、消费者在、事件类也建了，就是没人发），
 * 后者的事件实际由 payment-service 用自己的 RabbitTemplate 发出。
 * 留着不会报错，但会让"这个事件到底谁在发"变成回答不了的问题。
 */
@Slf4j
@Component
public class OrderEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public OrderEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishOrderCreated(OrderCreatedEvent event) {
        // 带上 CorrelationData：确认回调靠它的 id 才能指出"是哪一条消息没到 broker"，
        // 否则只留下一句"有消息丢了"，排查时无从对上号
        rabbitTemplate.convertAndSend(
                OrderEventConfig.ORDER_EXCHANGE,
                OrderEventConfig.ORDER_CREATED_KEY,
                event,
                new CorrelationData(event.getOrderNo()));
        log.info("[MQ] 订单创建事件已发布: orderNo={}, amount={}", event.getOrderNo(), event.getTotalAmount());
    }
}
