package yumefusaka.envoymart.orderservice.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.orderservice.entity.EventOutboxEntity;
import yumefusaka.envoymart.orderservice.mapper.EventOutboxMapper;

/**
 * 发件箱写入器 —— <b>业务事务内唯一该调的发布接口</b>。
 * <p>
 * 它做的只有一件事：把「待发布的事件」写进 {@code event_outbox}，与调用方在<b>同一个事务</b>里。
 * 真正的发送交给 {@link OutboxRelay}，在提交之后。
 * <p>
 * <b>{@code REQUIRED} 是刻意的，不是随手写的默认值。</b>传播行为必须是「加入调用方事务」：
 * 换成 {@code REQUIRES_NEW}，发件箱行会先提交、业务行随后回滚，于是产生一条
 * 「事件发了、状态没变」的孤儿——那正是这套机制要消灭的分叉之一。
 */
@Slf4j
@Component
public class OutboxWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EventOutboxMapper outboxMapper;

    public OutboxWriter(EventOutboxMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    /**
     * 把一条事件写进发件箱。序列化在这里完成一次：载荷此后是不透明的字符串，
     * 投递器与消费方各自按自己的契约反序列化，互不牵连。
     *
     * @param aggregateId 分区键（订单号）。同一条业务记录的事件据此保序
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void append(String eventType, String aggregateId, String exchange, String routingKey, Object payload) {
        EventOutboxEntity row = new EventOutboxEntity();
        row.setEventType(eventType);
        row.setAggregateId(aggregateId);
        row.setExchangeName(exchange);
        row.setRoutingKey(routingKey);
        row.setPayload(MAPPER.writeValueAsString(payload));
        row.setCreatedAt(Times.now());
        row.setAttempts(0);
        outboxMapper.insert(row);
        log.debug("[Outbox] 已登记待发布事件 type={} aggregate={} id={}", eventType, aggregateId, row.getId());
    }
}