package yumefusaka.envoymart.orderservice.mq;

import io.seata.spring.annotation.GlobalLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.orderservice.mapper.EventOutboxMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 单条投递的事务边界：全局锁核验和本地行锁都通过后才允许接触 broker。 */
@Slf4j
@Component
public class OutboxDispatchTransaction {
    private static final int CONFIRM_SECONDS = 5;
    private final EventOutboxMapper mapper;
    private final RabbitTemplate rabbit;

    public enum Outcome { SKIPPED, SENT, RETRY }

    public OutboxDispatchTransaction(EventOutboxMapper mapper, RabbitTemplate rabbit) {
        this.mapper = mapper;
        this.rabbit = rabbit;
    }

    @GlobalLock(lockRetryTimes = 0)
    @Transactional
    public Outcome dispatch(Long id) {
        if (org.apache.seata.core.context.RootContext.inGlobalTransaction()) {
            throw new IllegalStateException("发件箱投递不能加入尚未决议的全局事务");
        }
        var row = mapper.lockPending(id);
        if (row == null) return Outcome.SKIPPED;
        try {
            var correlation = new CorrelationData(row.getEventType() + ":" + row.getAggregateId() + ":" + row.getId());
            var message = MessageBuilder.withBody(row.getPayload().getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
            rabbit.send(row.getExchangeName(), row.getRoutingKey(), message, correlation);
            var confirm = correlation.getFuture().get(CONFIRM_SECONDS, TimeUnit.SECONDS);
            if (!confirm.ack()) throw new IllegalStateException("broker拒绝发布确认：" + confirm.reason());
            if (correlation.getReturned() != null) throw new IllegalStateException("broker确认但消息无法路由到队列");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            String summary = reason.length() > 500 ? reason.substring(0, 500) : reason;
            if (mapper.markFailed(id, summary) != 1) throw new IllegalStateException("发件箱失败状态保存失败", failure);
            log.warn("[Outbox] 阶段=投递失败将重试 id={} type={} errorType={}", id, row.getEventType(), failure.getClass().getSimpleName());
            return Outcome.RETRY;
        }
        // broker确认与数据库提交之间崩溃仍可能重投，下游继续按原消息业务键去重。
        if (mapper.markSent(id) != 1) throw new IllegalStateException("发件箱成功状态保存失败");
        return Outcome.SENT;
    }
}
