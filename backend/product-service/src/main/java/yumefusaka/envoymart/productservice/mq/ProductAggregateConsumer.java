package yumefusaka.envoymart.productservice.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.productservice.service.impl.ProductAggregateService;

/**
 * 商品派生数据的入站消费者。
 * <p>
 * <b>两个监听器都把异常抛出去，不在这里 try/catch 吞掉。</b>吞掉等于承认"处理过了"，
 * 消息被 ack 掉、永久消失，而商品上的那个数字就一直停在旧值——下次谁看都是错的，
 * 且没有任何信号。抛出去则由容器按统一配置重试、耗尽后进死信队列等人看。
 * <p>
 * 唯一"吞掉"的情况在 {@link ProductAggregateService} 里，且都是**重试也不会变好**的
 * 情形（商品不存在、订单行数据不完整）：那些不进死信，只记日志。
 */
@Slf4j
@Component
public class ProductAggregateConsumer {

    private final ProductAggregateService aggregateService;

    public ProductAggregateConsumer(ProductAggregateService aggregateService) {
        this.aggregateService = aggregateService;
    }

    @RabbitListener(queues = ProductAggregateConfig.REVIEW_AGGREGATE_QUEUE)
    public void onReviewAggregate(ReviewAggregateEvent event) {
        log.info("[MQ] 收到评价聚合: spuId={} avg={} count={}",
                event.spuId(), event.ratingAvg(), event.reviewCount());
        aggregateService.applyReviewAggregate(event);
    }

    @RabbitListener(queues = ProductAggregateConfig.SALES_QUEUE)
    public void onOrderPaid(OrderPaidEvent event) {
        log.info("[MQ] 收到支付完成: orderNo={} 订单行={}",
                event.orderNo(), event.items() == null ? 0 : event.items().size());
        aggregateService.applyPaidOrder(event);
    }
}
