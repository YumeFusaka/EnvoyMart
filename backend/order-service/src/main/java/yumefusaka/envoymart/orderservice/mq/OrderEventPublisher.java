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

    /**
     * 订单已支付 —— 由 {@code markPaid} 在状态真正推进之后调用，商品服务据此累加销量。
     * <p>
     * <b>失败要抛出去，不在这里吞掉。</b>调用方是「支付完成」的 MQ 消费者：抛出去会 nack
     * 重试，重试时订单还是待支付（事务一起回滚了），于是走的是同一条正常路径、
     * 重新发一次事件。吞掉的话就是一条已支付的订单永远不计销量，而且没有任何信号——
     * 这与 {@code refundPaidButClosedOrder} 的取舍是同一条理由。
     * <p>
     * <b>发布点因此放在事务提交之前，这是想清楚后选的。</b>提交后再发的话，消息不会早到，
     * 但发送失败时事务已经落地、没有任何东西能重放它；而"消息早于提交到达"在这里无害：
     * 消费方（商品服务）只读 {@code product_spu}、写自己的台账，从不回读订单表。
     * 至于「消息发出去了、事务却回滚了」——重试会把这一单重新走一遍，而台账的
     * {@code (order_id, spu_id)} 唯一约束让第二次发布是幂等的，销量不会数两遍。
     */
    public void publishOrderPaid(OrderPaidEvent event) {
        rabbitTemplate.convertAndSend(
                OrderEventConfig.ORDER_EXCHANGE,
                OrderEventConfig.ORDER_PAID_KEY,
                event,
                new CorrelationData("order-paid-" + event.orderNo()));
        log.info("[MQ] 订单支付完成事件已发布: orderNo={}, 订单行={}",
                event.orderNo(), event.items() == null ? 0 : event.items().size());
    }
}
