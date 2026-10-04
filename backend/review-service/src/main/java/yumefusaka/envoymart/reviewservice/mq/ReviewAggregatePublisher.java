package yumefusaka.envoymart.reviewservice.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import yumefusaka.envoymart.reviewservice.service.ReviewAggregateReader;

/**
 * 把商品的评分聚合变更播出去，让 product-service 更新它那份冗余。
 * <p>
 * <b>为什么必须在事务提交后发</b>：事件带的是全量聚合值，而消费者拿到就写。
 * 在提交前发，消费者可能先读到（此时还看不见未提交的那条评价）算出旧值写下去，
 * 而新值再没有任何人会写——商品侧就永久停在少一条的统计上。
 */
@Slf4j
@Component
public class ReviewAggregatePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ReviewAggregateReader aggregateReader;

    public ReviewAggregatePublisher(RabbitTemplate rabbitTemplate, ReviewAggregateReader aggregateReader) {
        this.rabbitTemplate = rabbitTemplate;
        this.aggregateReader = aggregateReader;
    }

    /**
     * 注册到当前事务的 {@code afterCommit}。没有事务时立即发。
     * <p>
     * <b>提交后才去读聚合，而不是把事务内的快照捎出来</b>：两个用户几乎同时评价同一个商品时，
     * 事务内的快照各自只看得见自己那一条，谁后提交谁的快照才是对的——但拿着旧快照的那个
     * 完全可能后发。提交后重读，两个事务都能算出「两条都在」的同一个结果。
     */
    public void publishAfterCommit(Long spuId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(spuId);
                }
            });
        } else {
            send(spuId);
        }
    }

    private void send(Long spuId) {
        ReviewAggregateReader.Snapshot snapshot = aggregateReader.read(spuId);
        ReviewAggregateEvent event = new ReviewAggregateEvent(
                spuId, snapshot.average(), snapshot.total(), snapshot.version());
        try {
            rabbitTemplate.convertAndSend(
                    ReviewEventConfig.REVIEW_EXCHANGE,
                    ReviewEventConfig.AGGREGATE_CHANGED_KEY,
                    event,
                    new CorrelationData("review-aggregate-" + spuId));
            log.info("[MQ] 评价聚合事件已发布: spuId={}, avg={}, count={}, version={}",
                    spuId, snapshot.average(), snapshot.total(), snapshot.version());
        } catch (Exception e) {
            // 这里只能记日志。用户的评价**已经落库了** —— 抛出去回滚不了它，
            // 只会把一次成功的提交变成一个 500 报给用户，而消息照样没发出去。
            //
            // 代价说清楚：商品侧的评分会一直停在旧值，直到这个商品的下一条评价
            // （或一次聚合重建）把它追上。这是"派生数据慢一拍"，不是数据损坏
            log.error("[MQ] 评价聚合事件发布失败，商品侧评分将保持旧值，等待下一次评价或聚合重建: spuId={}",
                    spuId, e);
        }
    }
}
