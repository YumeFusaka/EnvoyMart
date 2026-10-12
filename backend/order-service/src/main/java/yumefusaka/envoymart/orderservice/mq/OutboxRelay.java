package yumefusaka.envoymart.orderservice.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.orderservice.entity.EventOutboxEntity;
import yumefusaka.envoymart.orderservice.mapper.EventOutboxMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发件箱投递器 —— 扫描候选行，交给独立事务代理核验全局锁后投递。
 * <p>
 * <b>为什么不是「提交后立刻发一次」。</b>那是最省事的做法，也是唯一会在崩溃时丢事件的
 * 做法：提交成功、发送还没执行，进程就没了，那一行永远留在库里没人管。
 * 候选行可在Seata本地阶段提交后出现，但全局事务仍可能回滚，因此扫描不授予发布许可。
 * 每条候选都由事务代理锁定、核验全局锁并重读；发送失败或进程重启，
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
    private final OutboxDispatchTransaction dispatch;

    public OutboxRelay(EventOutboxMapper outboxMapper, OutboxDispatchTransaction dispatch) {
        this.outboxMapper = outboxMapper;
        this.dispatch = dispatch;
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
                if (dispatch.dispatch(row.getId()) == OutboxDispatchTransaction.Outcome.SENT) sent++;
            } catch (RuntimeException e) {
                // 未决全局锁、TC不可达或事务提交失败均不能在代理之外修改原行。
                log.warn("[Outbox] 阶段=等待事务屏障或下轮重试 id={} type={} errorType={}",
                        row.getId(), row.getEventType(), e.getClass().getSimpleName());
            }
        }
        log.info("[Outbox] 本轮投递 {}/{} 条", sent, pending.size());
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
