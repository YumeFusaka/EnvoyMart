package yumefusaka.envoymart.orderservice.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 订单事件发布器：<b>把事件登记进发件箱</b>（不再直接发 MQ）。
 * <p>
 * 发送动作交给 {@link OutboxRelay}，理由见 {@link #publishOrderPaid}。
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

    private final OutboxWriter outboxWriter;

    public OrderEventPublisher(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    public void publishOrderCreated(OrderCreatedEvent event) {
        outboxWriter.append(OrderEventConfig.ORDER_CREATED_KEY, event.getOrderNo(),
                OrderEventConfig.ORDER_EXCHANGE, OrderEventConfig.ORDER_CREATED_KEY, event);
        log.info("[MQ] 订单创建事件已登记待发布: orderNo={}, amount={}",
                event.getOrderNo(), event.getTotalAmount());
    }

    /**
     * 订单已支付 —— 由 {@code markPaid} 在状态真正推进之后调用，商品服务据此累加销量。
     * <p>
     * <b>改成写发件箱了（U60）。</b>原先它直接在业务事务里 {@code convertAndSend}，
     * 那段注释把取舍写成了「发送失败能让整条链路重试」——但那个重试依赖调用方抛异常、
     * MQ nack，而真正要防的不是「发送返回错误」，是<b>提交之前的那个崩溃窗口</b>：
     * 消息已经离开进程、事务还没落地，进程一挂，状态没了、下游却已经按新状态动作了。
     * <p>
     * 现在它只往 {@code event_outbox} 写一行（与业务行同一个事务），发送由
     * {@link OutboxRelay} 在提交后扫描并投递。于是两种分叉同时消失：
     * 提交前崩溃 → 行和状态一起回滚；提交后崩溃 → 行还在库里，下一轮扫描补发。
     */
    public void publishOrderPaid(OrderPaidEvent event) {
        outboxWriter.append(OrderEventConfig.ORDER_PAID_KEY, event.orderNo(),
                OrderEventConfig.ORDER_EXCHANGE, OrderEventConfig.ORDER_PAID_KEY, event);
        log.info("[MQ] 订单支付完成事件已登记待发布: orderNo={}, 订单行={}",
                event.orderNo(), event.items() == null ? 0 : event.items().size());
    }
}
