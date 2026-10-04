package yumefusaka.envoymart.orderservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.orderservice.entity.SupportTicketEntity;
import yumefusaka.envoymart.orderservice.entity.SupportTicketMessageEntity;
import yumefusaka.envoymart.orderservice.mapper.SupportTicketMapper;
import yumefusaka.envoymart.orderservice.mapper.SupportTicketMessageMapper;
import yumefusaka.envoymart.orderservice.model.TicketMessageView;
import yumefusaka.envoymart.orderservice.model.TicketSenderType;
import yumefusaka.envoymart.orderservice.model.TicketStatus;
import yumefusaka.envoymart.orderservice.service.TicketDomainService;
import yumefusaka.envoymart.orderservice.service.TicketNotifier;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class TicketDomainServiceImpl implements TicketDomainService {

    private static final String AUTO_CLOSE_NOTICE =
            "工单标记为「已解决」后超过 " + RESOLVE_CONFIRM_DAYS + " 天未确认，系统已自动关闭。"
                    + "如果问题仍在，请新开工单。";

    private final SupportTicketMapper ticketMapper;
    private final SupportTicketMessageMapper messageMapper;
    private final TicketNotifier notifier;

    public TicketDomainServiceImpl(SupportTicketMapper ticketMapper,
                                   SupportTicketMessageMapper messageMapper,
                                   TicketNotifier notifier) {
        this.ticketMapper = ticketMapper;
        this.messageMapper = messageMapper;
        this.notifier = notifier;
    }

    @Override
    public SupportTicketEntity requireOwned(Long ticketId, String userId) {
        SupportTicketEntity ticket = ticketId == null ? null : ticketMapper.selectOne(
                new LambdaQueryWrapper<SupportTicketEntity>()
                        .eq(SupportTicketEntity::getId, ticketId)
                        .eq(SupportTicketEntity::getUserId, userId));
        if (ticket == null) {
            throw new IllegalArgumentException("工单不存在");
        }
        return ticket;
    }

    @Override
    public SupportTicketEntity requireExists(Long ticketId) {
        SupportTicketEntity ticket = ticketId == null ? null : ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new IllegalArgumentException("工单不存在");
        }
        return ticket;
    }

    @Override
    public List<TicketMessageView> messagesOf(Long ticketId) {
        return messageMapper.selectList(new LambdaQueryWrapper<SupportTicketMessageEntity>()
                        .eq(SupportTicketMessageEntity::getTicketId, ticketId)
                        .orderByAsc(SupportTicketMessageEntity::getId))
                .stream()
                .map(TicketMessageView::from)
                .toList();
    }

    @Override
    public TicketMessageView appendMessage(SupportTicketEntity ticket, TicketSenderType senderType,
                                           String senderId, String content) {
        LocalDateTime now = Times.now();
        SupportTicketMessageEntity message = insertMessage(ticket.getId(), senderType, senderId, content, now);

        // 球权与活跃时间跟着最后一条人工消息走。
        //
        // 这里刻意**不加状态条件**：调用方已经通过 requireOpenForConversation 或 transit
        // 拿到了这一行的排他锁（两条都是对该行的 UPDATE，锁持有到事务提交），此后状态
        // 不可能再被别人改掉。反过来若在这里写 status <> 'CLOSED'，客服关闭工单时那条
        // 「工单已关闭：原因」的消息会连自己一起挡下来 —— 关闭动作留下一句没说出口的
        // 解释，而接口一路成功。
        ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicketEntity>()
                .eq(SupportTicketEntity::getId, ticket.getId())
                .set(SupportTicketEntity::getLastReplyBy, senderType.name())
                .set(SupportTicketEntity::getUpdatedAt, now));
        ticket.setLastReplyBy(senderType.name());
        ticket.setUpdatedAt(now);
        return TicketMessageView.from(message);
    }

    @Override
    public void requireOpenForConversation(SupportTicketEntity ticket, String action) {
        int rows = ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicketEntity>()
                .eq(SupportTicketEntity::getId, ticket.getId())
                .ne(SupportTicketEntity::getStatus, TicketStatus.CLOSED.name())
                .set(SupportTicketEntity::getUpdatedAt, Times.now()));
        if (rows == 0) {
            throw new IllegalStateException("工单已关闭，不能再" + action);
        }
    }

    @Override
    public void handOver(SupportTicketEntity ticket, TicketSenderType side) {
        ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicketEntity>()
                .eq(SupportTicketEntity::getId, ticket.getId())
                .set(SupportTicketEntity::getLastReplyBy, side.name()));
        ticket.setLastReplyBy(side.name());
    }

    @Override
    public void appendSystemMessage(SupportTicketEntity ticket, String content) {
        insertMessage(ticket.getId(), TicketSenderType.SYSTEM, null, content, Times.now());
    }

    @Override
    public void transit(SupportTicketEntity ticket, TicketStatus target, String closeReason) {
        TicketStatus current = TicketStatus.parse(ticket.getStatus());
        if (!current.canTransitTo(target)) {
            throw new IllegalStateException(
                    "工单当前是「" + current.text() + "」，不能执行这个操作");
        }

        LocalDateTime now = Times.now();
        LambdaUpdateWrapper<SupportTicketEntity> update = new LambdaUpdateWrapper<SupportTicketEntity>()
                .eq(SupportTicketEntity::getId, ticket.getId())
                // 条件更新：从"读到的状态"出发。两个操作并发时后到的那条命中 0 行，
                // 报 409 让人刷新 —— 而不是把先到的那个结果覆盖掉
                .eq(SupportTicketEntity::getStatus, current.name())
                .set(SupportTicketEntity::getStatus, target.name())
                .set(SupportTicketEntity::getUpdatedAt, now);
        if (target == TicketStatus.PROCESSING) {
            // 重开：把解决时间清掉。留着旧值会让下一轮"计时"直接从过去某个点开始，
            // 重开的工单可能当场就被超时任务扫走
            update.set(SupportTicketEntity::getResolvedAt, null);
        }
        if (target == TicketStatus.RESOLVED) {
            update.set(SupportTicketEntity::getResolvedAt, now);
        }
        if (target == TicketStatus.CLOSED) {
            update.set(SupportTicketEntity::getClosedAt, now)
                    .set(SupportTicketEntity::getCloseReason, closeReason);
        }
        if (ticketMapper.update(null, update) == 0) {
            throw new IllegalStateException("工单状态已变更，请刷新后重试");
        }

        // 让调用方手里的对象跟上，省掉一次回查
        ticket.setStatus(target.name());
        ticket.setUpdatedAt(now);
        if (target == TicketStatus.PROCESSING) {
            ticket.setResolvedAt(null);
        }
        if (target == TicketStatus.RESOLVED) {
            ticket.setResolvedAt(now);
        }
        if (target == TicketStatus.CLOSED) {
            ticket.setClosedAt(now);
            ticket.setCloseReason(closeReason);
        }
    }

    /**
     * 事务由调度器跨 bean 调用进入，覆盖整批。<b>但它并不是"一条失败回滚全批"</b>：
     * 循环里显式接住了 {@link IllegalStateException}，被并发的用户确认抢先的工单跳过、
     * 其余照常提交。{@code @Transactional} 真正买到的是<b>逐条内部的成对性</b> ——
     * {@code transit} 与那条"系统已自动关闭"的消息要么都生效要么都不生效，
     * 不会出现一条状态已关闭却没有解释的工单。
     * <p>
     * 非 {@code IllegalStateException} 的异常（数据库断了之类）不受这个 catch 保护，
     * 会照常冒出整批回滚，下一趟重来 —— 那才是需要整体重试的失败。
     * <p>
     * 条件更新保证已处理过的下趟不会再被扫到（状态已不是 RESOLVED）。
     */
    @Override
    @Transactional
    public int autoCloseExpired(int batchSize) {
        LocalDateTime deadline = Times.now().minusDays(RESOLVE_CONFIRM_DAYS);
        List<SupportTicketEntity> expired = ticketMapper.selectList(
                new LambdaQueryWrapper<SupportTicketEntity>()
                        .eq(SupportTicketEntity::getStatus, TicketStatus.RESOLVED.name())
                        .lt(SupportTicketEntity::getResolvedAt, deadline)
                        .orderByAsc(SupportTicketEntity::getId)
                        .last("limit " + Math.max(1, batchSize)));

        int closed = 0;
        for (SupportTicketEntity ticket : expired) {
            try {
                // 进入这里前可能已被用户确认（resolvedAt 在被条件更新挡住），
                // transit 会以 0 行失败，跳过；appendSystemMessage 也就不会执行，
                // 不会给一条已经关掉的工单补"自动关闭"的解释
                transit(ticket, TicketStatus.CLOSED, CLOSE_BY_TIMEOUT);
                appendSystemMessage(ticket, AUTO_CLOSE_NOTICE);
                // 自动关闭把工单移出「等你回应」集合。不推的话，用户的角标会一直挂着一个
                // 实际已经关闭的工单，直到他下次手动刷新 —— 而这条链路的全部意义就是
                // 不让用户靠刷新去发现状态已经变了
                notifier.notifyAwaiting(ticket.getUserId());
                closed++;
            } catch (IllegalStateException e) {
                log.info("[Ticket] 自动关闭跳过（已被并发处理）ticketNo={} reason={}",
                        ticket.getTicketNo(), e.getMessage());
            }
        }
        if (closed > 0) {
            log.info("[Ticket] 自动关闭 {} 条已解决超期的工单（本批扫出 {} 条）", closed, expired.size());
        }
        return closed;
    }

    private SupportTicketMessageEntity insertMessage(Long ticketId, TicketSenderType senderType,
                                                     String senderId, String content, LocalDateTime now) {
        SupportTicketMessageEntity message = new SupportTicketMessageEntity();
        message.setTicketId(ticketId);
        message.setSenderType(senderType.name());
        message.setSenderId(senderId);
        message.setContent(content);
        message.setCreatedAt(now);
        messageMapper.insert(message);
        return message;
    }
}
