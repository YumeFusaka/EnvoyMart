package yumefusaka.envoymart.paymentservice.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.paymentservice.entity.EventOutboxEntity;
import yumefusaka.envoymart.paymentservice.mapper.EventOutboxMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 发件箱投递器 —— 把已提交的发件箱行真正发到 broker，并标记 {@code sent_at}。
 * <p>
 * <b>为什么不是「提交后立刻发一次」。</b>那是最省事的做法，也是唯一会在崩溃时丢事件的
 * 做法：提交成功、发送还没执行，进程就没了，那一行永远留在库里没人管。
 * 这里用「轮询扫描未发送的行」把它变成可自愈的：发送失败或进程重启，
 * 下一轮扫描自然会把它捡起来。代价是最坏情况下多等一个扫描周期（这里是 500ms），
 * 而这份代价换来的是「只要行在库里，事件迟早会发」这个更强的保证。
 * <p>
 * <b>不删行，只标记。</b>{@code sent_at} 保留下来，既是「这条发过了」的证据，
 * 也是排查「下游说没收到」时的第一手材料 —— 删掉就只剩重试计数可以猜。
 * 清理属于独立的归档动作，不该和投递混在一个职责里。
 * <p>
 * <b>单条失败不拖累整批。</b>一条发送异常时只记 last_error 并继续下一条；
 * 否则一条坏消息会把整张表后面的消息全堵住 —— 那是「一个用户的订单号写错，
 * 全平台事件停发」这种最不该发生的故障。
 */
@Slf4j
@Component
public class OutboxRelay {

    /** 一轮最多投多少条。给积压一个上限，避免一轮扫描把内存拉满 */
    private static final int BATCH = 100;

    private final EventOutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;

    public OutboxRelay(EventOutboxMapper outboxMapper, RabbitTemplate rabbitTemplate) {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelayString = "${envoymart.outbox.relay-interval-ms:500}")
    public void relay() {
        List<EventOutboxEntity> pending = outboxMapper.selectList(
                new LambdaQueryWrapper<EventOutboxEntity>()
                        .isNull(EventOutboxEntity::getSentAt)
                        .orderByAsc(EventOutboxEntity::getId)
                        .last("limit " + BATCH));
        if (pending.isEmpty()) {
            return;
        }

        int sent = 0;
        for (EventOutboxEntity row : pending) {
            try {
                rabbitTemplate.send(row.getExchangeName(), row.getRoutingKey(), toMessage(row),
                        new CorrelationData(row.getEventType() + ":" + row.getAggregateId() + ":" + row.getId()));
                markSent(row);
                sent++;
            } catch (RuntimeException e) {
                // 不抛出去：抛出去会让本轮剩下的消息一起被跳过，一条坏消息堵住整批。
                // 失败留痕在 last_error，下一轮继续重试
                markFailed(row, e);
            }
        }
        log.info("[Outbox] 本轮投递 {}/{} 条", sent, pending.size());
    }

    /**
     * 把发件箱里已经序列化好的 JSON 文本装成消息。
     * <p>
     * <b>必须走 {@code send} 而不是 {@code convertAndSend}。</b>载荷写入发件箱时已经序列化一次，
     * 再交给 {@code JacksonJsonMessageConverter} 会把这段文本当成一个 Java {@code String}
     * 重新序列化，body 变成 {@code "{\"orderId\":339,...}"} —— 一个 JSON 字符串字面量。
     * 消费端按 {@code PaymentCompletedEvent} 反序列化时报「no String-argument constructor」，
     * 支付完成事件转不成对象、nack 进死信，订单永远停在待支付。
     * <p>
     * 这里显式声明 {@code content-type: application/json}，消费端的 Jackson 转换器据此
     * 把字节直接反序列化成目标类型。
     */
    private Message toMessage(EventOutboxEntity row) {
        return MessageBuilder.withBody(row.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
    }

    private void markSent(EventOutboxEntity row) {
        EventOutboxEntity update = new EventOutboxEntity();
        update.setId(row.getId());
        update.setSentAt(Times.now());
        update.setAttempts((row.getAttempts() == null ? 0 : row.getAttempts()) + 1);
        update.setLastError(null);
        outboxMapper.updateById(update);
    }

    private void markFailed(EventOutboxEntity row, RuntimeException e) {
        EventOutboxEntity update = new EventOutboxEntity();
        update.setId(row.getId());
        update.setAttempts((row.getAttempts() == null ? 0 : row.getAttempts()) + 1);
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        update.setLastError(message.length() > 500 ? message.substring(0, 500) : message);
        outboxMapper.updateById(update);
        log.warn("[Outbox] 投递失败将重试 id={} type={} attempts={}: {}",
                row.getId(), row.getEventType(), update.getAttempts(), message);
    }

    /** 待投递条数 —— 它就是这套机制的积压指标，比日志耐久 */
    public long pendingCount() {
        return outboxMapper.selectCount(
                new LambdaQueryWrapper<EventOutboxEntity>().isNull(EventOutboxEntity::getSentAt));
    }

    /** 积压超过这个时长的行数：真出问题时这个数字会持续上涨，而正常情况应恒为 0 */
    public long stalledCount(LocalDateTime before) {
        return outboxMapper.selectCount(
                new LambdaQueryWrapper<EventOutboxEntity>()
                        .isNull(EventOutboxEntity::getSentAt)
                        .lt(EventOutboxEntity::getCreatedAt, before));
    }
}
