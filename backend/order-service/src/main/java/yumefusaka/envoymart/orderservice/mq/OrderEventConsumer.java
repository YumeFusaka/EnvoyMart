package yumefusaka.envoymart.orderservice.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.orderservice.service.OrderDomainService;

/**
 * 订单事件消费者：异步处理订单相关事件。
 * <p>
 * <b>异步只承接"失败可以重来"的副作用</b>（通知、风控、数据同步）。扣库存、清购物车这类
 * 必须与下单同时成立的事留在 {@code checkout} 的同步路径里——挪到这里会让"下单成功"
 * 和"库存真的扣了"之间重新出现窗口，正是之前那个"订单建了、库存没扣"的老问题。
 */
@Slf4j
@Component
public class OrderEventConsumer {

    private final OrderDomainService orderDomainService;

    public OrderEventConsumer(OrderDomainService orderDomainService) {
        this.orderDomainService = orderDomainService;
    }

    @RabbitListener(queues = OrderEventConfig.ORDER_CREATED_QUEUE)
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("[MQ.Consumer] 收到订单创建事件: orderNo={}, 异步处理通知等后续流程", event.getOrderNo());
    }

    /**
     * 支付完成 → 把订单推进到已支付。
     * <p>
     * <b>这条链路曾经是断的</b>：消费者只打一行日志，全仓库没有任何地方把订单写成 PAID，
     * 于是 {@code cancelOrder} 里那句"已支付订单请走退款流程"永远不会命中——用户付完款
     * 照样能取消订单，库存原路退回、钱却不退，也没有任何退款记录。
     */
    @RabbitListener(queues = OrderEventConfig.PAYMENT_COMPLETED_QUEUE)
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("[MQ.Consumer] 收到支付完成事件: orderNo={}, txNo={}",
                event.getOrderNo(), event.getTransactionNo());
        orderDomainService.markPaid(event.getOrderId());
    }

    @RabbitListener(queues = OrderEventConfig.ORDER_DLX_QUEUE)
    public void handleDeadLetter(String message) {
        log.warn("[MQ.DLX] 收到死信消息: {}, 进入人工处理流程", message);
    }
}
