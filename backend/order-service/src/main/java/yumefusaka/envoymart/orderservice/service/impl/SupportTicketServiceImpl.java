package yumefusaka.envoymart.orderservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;
import yumefusaka.envoymart.orderservice.entity.SupportTicketEntity;
import yumefusaka.envoymart.orderservice.mapper.OrderMapper;
import yumefusaka.envoymart.orderservice.mapper.SupportTicketMapper;
import yumefusaka.envoymart.orderservice.model.CreateTicketRequest;
import yumefusaka.envoymart.orderservice.model.TicketCategory;
import yumefusaka.envoymart.orderservice.model.TicketDetailResponse;
import yumefusaka.envoymart.orderservice.model.TicketResponse;
import yumefusaka.envoymart.orderservice.model.TicketSenderType;
import yumefusaka.envoymart.orderservice.model.TicketStatus;
import yumefusaka.envoymart.orderservice.model.TicketSummary;
import yumefusaka.envoymart.orderservice.service.SupportTicketService;
import yumefusaka.envoymart.orderservice.service.TicketDomainService;
import yumefusaka.envoymart.orderservice.service.TicketNotifier;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class SupportTicketServiceImpl implements SupportTicketService {

    private static final int MAX_PAGE_SIZE = 50;

    private final SupportTicketMapper ticketMapper;
    private final OrderMapper orderMapper;
    private final TicketDomainService domain;
    private final TicketNotifier notifier;

    public SupportTicketServiceImpl(SupportTicketMapper ticketMapper, OrderMapper orderMapper,
                                    TicketDomainService domain, TicketNotifier notifier) {
        this.ticketMapper = ticketMapper;
        this.orderMapper = orderMapper;
        this.domain = domain;
        this.notifier = notifier;
    }

    @Override
    @Transactional
    public TicketDetailResponse create(String userId, CreateTicketRequest request) {
        TicketCategory category = TicketCategory.parse(request.getCategory());

        // 关联订单必须是自己：把用户 B 的订单号挂进用户 A 的工单，客服拿着
        // 这个线索去查，问的是完全不相干的人——而两个用户都不会察觉
        OrderEntity order = null;
        if (request.getOrderId() != null) {
            order = orderMapper.selectById(request.getOrderId());
            if (order == null || !userId.equals(order.getUserId())) {
                throw new IllegalArgumentException("订单不存在");
            }
        }

        LocalDateTime now = Times.now();
        SupportTicketEntity ticket = new SupportTicketEntity();
        ticket.setTicketNo(generateTicketNo());
        ticket.setUserId(userId);
        ticket.setOrderId(order == null ? null : order.getId());
        ticket.setOrderNo(order == null ? null : order.getOrderNo());
        ticket.setCategory(category.name());
        ticket.setTitle(request.getTitle().trim());
        ticket.setStatus(TicketStatus.OPEN.name());
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        ticketMapper.insert(ticket);

        // 问题描述就是第一条消息。不落这一条的话，客服点开工单只看得到标题，
        // 用户说了什么得另外找——而工单的上下文全在消息流里
        domain.appendMessage(ticket, TicketSenderType.USER, userId, request.getContent().trim());
        return TicketDetailResponse.of(ticket, domain.messagesOf(ticket.getId()));
    }

    @Override
    public PageResult<TicketResponse> listMine(String userId, String status, Integer page, Integer size) {
        // 非法状态 400 而不是静默忽略：拼错的状态值被忽略时，页面会一本正经地
        // 展示"全部工单"，而使用的人以为自己是筛过的
        TicketStatus filter = status == null || status.isBlank() ? null : TicketStatus.parse(status);

        int zeroBasedPage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);

        Page<SupportTicketEntity> result = ticketMapper.selectPage(
                new Page<>(zeroBasedPage + 1L, safeSize),
                new LambdaQueryWrapper<SupportTicketEntity>()
                        .eq(SupportTicketEntity::getUserId, userId)
                        .eq(filter != null, SupportTicketEntity::getStatus, filter == null ? null : filter.name())
                        .orderByDesc(SupportTicketEntity::getUpdatedAt)
                        .orderByDesc(SupportTicketEntity::getId));

        List<TicketResponse> records = result.getRecords().stream().map(TicketResponse::from).toList();
        return PageResult.<TicketResponse>builder()
                .records(records)
                .total(result.getTotal())
                .page(zeroBasedPage)
                .size(safeSize)
                .build();
    }

    @Override
    public TicketSummary summary(String userId) {
        // 一条工单都没有时聚合 SQL 仍返回一行（count(*) = 0），不会是 null；
        // 真为 null 只可能是映射出了问题，返回一个全零比把 NPE 甩给用户好
        TicketSummary summary = ticketMapper.countByUser(userId);
        return summary == null ? new TicketSummary() : summary;
    }

    @Override
    public TicketDetailResponse detail(String userId, Long ticketId) {
        SupportTicketEntity ticket = domain.requireOwned(ticketId, userId);
        return TicketDetailResponse.of(ticket, domain.messagesOf(ticketId));
    }

    @Override
    @Transactional
    public TicketDetailResponse addMessage(String userId, Long ticketId, String content) {
        SupportTicketEntity ticket = domain.requireOwned(ticketId, userId);
        domain.requireOpenForConversation(ticket, "补充说明");
        domain.appendMessage(ticket, TicketSenderType.USER, userId, content.trim());
        // 用户一开口，球权回到客服侧，本用户的「等你回应」可能就此清零 —— 推一次
        notifier.notifyAwaiting(userId);
        return TicketDetailResponse.of(ticket, domain.messagesOf(ticketId));
    }

    @Override
    @Transactional
    public TicketDetailResponse close(String userId, Long ticketId) {
        SupportTicketEntity ticket = domain.requireOwned(ticketId, userId);
        // 从「已解决」关 = 用户确认；从其他状态关 = 用户不要了。
        // 用状态区分而不是再要一个原因输入——用户不欠我们一个解释，
        // 而客服侧的关闭原因必填，两端的要求不对称是有意的
        String reason = TicketStatus.RESOLVED.name().equals(ticket.getStatus())
                ? TicketDomainService.CLOSE_BY_USER_CONFIRMED
                : TicketDomainService.CLOSE_BY_USER_CANCELLED;
        domain.transit(ticket, TicketStatus.CLOSED, reason);
        notifier.notifyAwaiting(userId);
        return TicketDetailResponse.of(ticket, domain.messagesOf(ticketId));
    }

    @Override
    @Transactional
    public TicketDetailResponse reopen(String userId, Long ticketId, String content) {
        SupportTicketEntity ticket = domain.requireOwned(ticketId, userId);
        // 状态机本身允许 OPEN → PROCESSING（那是客服"首次回复接手"的动作），
        // 但用户的重开只对「已解决」有意义：对一条还没人处理过的工单说"重开"，
        // 它本来就是开着的
        if (!TicketStatus.RESOLVED.name().equals(ticket.getStatus())) {
            throw new IllegalStateException("只有已解决的工单可以重开");
        }
        domain.transit(ticket, TicketStatus.PROCESSING, null);
        if (content != null && !content.isBlank()) {
            domain.appendMessage(ticket, TicketSenderType.USER, userId, content.trim());
        } else {
            // 不带说明重开，球权仍要推回用户侧。落在 ADMIN 那边的话，工单不在客服的
            // 待回复队列里（那个队列看的就是 last_reply_by），用户以为重开即已送达，
            // 客服那边却看不见它 —— 一条不出声的工单，而两边都以为对方在处理
            domain.handOver(ticket, TicketSenderType.USER);
        }
        notifier.notifyAwaiting(userId);
        return TicketDetailResponse.of(ticket, domain.messagesOf(ticketId));
    }

    private String generateTicketNo() {
        return "TK" + DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(LocalDateTime.now())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
    }
}
