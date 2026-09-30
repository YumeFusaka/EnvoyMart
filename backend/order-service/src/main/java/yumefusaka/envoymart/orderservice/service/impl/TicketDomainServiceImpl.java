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

    public TicketDomainServiceImpl(SupportTicketMapper ticketMapper,
                                   SupportTicketMessageMapper messageMapper) {
        this.ticketMapper = ticketMapper;
        this.messageMapper = messageMapper;
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

        // 球权与活跃时间跟着最后一条人工消息走。用条件更新没必要 —— 这两个字段
        // 的并发语义是"最后写的赢"，而值本身来自刚刚插入的那条消息，不会算错
        ticketMapper.update(null, new LambdaUpdateWrapper<SupportTicketEntity>()
                .eq(SupportTicketEntity::getId, ticket.getId())
                .set(SupportTicketEntity::getLastReplyBy, senderType.name())
                .set(SupportTicketEntity::getUpdatedAt, now));
        ticket.setLastReplyBy(senderType.name());
        ticket.setUpdatedAt(now);
        return TicketMessageView.from(message);
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
     * {@code @Transactional} 由调度器跨 bean 调用进入，事务覆盖整批：
     * 一条失败回滚全批，下一趟重来（条件更新保证已处理过的不会重复处理）。
     * <p>
     * 个别条目在「扫出来」到「处理」的间隙被用户抢先确认，会以
     * {@link IllegalStateException} 的形式失败 —— 那是<b>预期的并发</b>，
     * 跳过它继续，不让一个人手快导致这一批全滚。
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
