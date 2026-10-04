package yumefusaka.envoymart.orderservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.orderservice.mapper.SupportTicketMapper;
import yumefusaka.envoymart.orderservice.model.TicketSummary;
import yumefusaka.envoymart.orderservice.model.TicketAwaitingPayload;
import yumefusaka.envoymart.orderservice.service.TicketNotifier;
import yumefusaka.envoymart.orderservice.service.TicketStreamHub;

import java.util.List;

@Slf4j
@Service
public class TicketNotifierImpl implements TicketNotifier {

    private final SupportTicketMapper ticketMapper;
    private final TicketStreamHub hub;

    public TicketNotifierImpl(SupportTicketMapper ticketMapper, TicketStreamHub hub) {
        this.ticketMapper = ticketMapper;
        this.hub = hub;
    }

    @Override
    public void notifyAwaiting(String userId) {
        try {
            TicketSummary summary = ticketMapper.countByUser(userId);
            List<Long> ids = ticketMapper.selectAwaitingIds(userId);
            hub.push(userId, new TicketAwaitingPayload(
                    summary == null ? 0 : summary.getAwaitingMe(), ids));
        } catch (Exception e) {
            // 推送是旁路：这里是唯一允许吞异常的地方 —— 业务结果已经落库，
            // 让「通知没发出去」把一次成功的回复变成失败，是本末倒置
            log.warn("[TicketSSE] 推送失败 userId={}：{}", userId, e.getMessage());
        }
    }

    @Override
    public void push(String userId, TicketAwaitingPayload payload) {
        try {
            hub.push(userId, payload);
        } catch (Exception e) {
            log.warn("[TicketSSE] 推送失败 userId={}：{}", userId, e.getMessage());
        }
    }
}
