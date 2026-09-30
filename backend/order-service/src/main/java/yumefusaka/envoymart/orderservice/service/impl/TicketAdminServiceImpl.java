package yumefusaka.envoymart.orderservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.orderservice.entity.SupportTicketEntity;
import yumefusaka.envoymart.orderservice.mapper.SupportTicketMapper;
import yumefusaka.envoymart.orderservice.model.TicketCategory;
import yumefusaka.envoymart.orderservice.model.TicketSenderType;
import yumefusaka.envoymart.orderservice.model.TicketStatus;
import yumefusaka.envoymart.orderservice.model.admin.AdminTicketDetail;
import yumefusaka.envoymart.orderservice.model.admin.AdminTicketQuery;
import yumefusaka.envoymart.orderservice.model.admin.AdminTicketSummary;
import yumefusaka.envoymart.orderservice.service.TicketAdminService;
import yumefusaka.envoymart.orderservice.service.TicketDomainService;

import java.util.List;

@Service
public class TicketAdminServiceImpl implements TicketAdminService {

    private final SupportTicketMapper ticketMapper;
    private final TicketDomainService domain;

    public TicketAdminServiceImpl(SupportTicketMapper ticketMapper, TicketDomainService domain) {
        this.ticketMapper = ticketMapper;
        this.domain = domain;
    }

    @Override
    public PageResult<AdminTicketSummary> list(AdminTicketQuery query) {
        // 非法状态/分类 400：客服拼错筛选值时，返回"全部工单"会被当成筛选生效
        TicketStatus status = blankToNull(query.getStatus()) == null
                ? null : TicketStatus.parse(query.getStatus());
        TicketCategory category = blankToNull(query.getCategory()) == null
                ? null : TicketCategory.parse(query.getCategory());

        Page<SupportTicketEntity> result = ticketMapper.selectPage(
                new Page<>(query.mpCurrent(), query.safeSize()),
                buildWrapper(query, status, category));

        List<AdminTicketSummary> records = result.getRecords().stream()
                .map(AdminTicketSummary::from)
                .toList();
        return PageResult.<AdminTicketSummary>builder()
                .records(records)
                .total(result.getTotal())
                .page(query.zeroBasedPage())
                .size(query.safeSize())
                .build();
    }

    @Override
    public AdminTicketDetail detail(Long ticketId) {
        SupportTicketEntity ticket = domain.requireExists(ticketId);
        return AdminTicketDetail.builder()
                .ticket(AdminTicketSummary.from(ticket))
                .closeReason(ticket.getCloseReason())
                .messages(domain.messagesOf(ticketId))
                .build();
    }

    @Override
    @Transactional
    public AdminTicketDetail reply(Long ticketId, String content, String operatorId) {
        SupportTicketEntity ticket = domain.requireExists(ticketId);
        requireNotClosed(ticket, "回复");
        // 首次回复即接手。让客服先点一下"接手"再发言是两步操作一个意图，
        // 现实中没人那么干——而没接手的工单在列表上仍显示"待处理"，是错的
        if (TicketStatus.OPEN.name().equals(ticket.getStatus())) {
            domain.transit(ticket, TicketStatus.PROCESSING, null);
        }
        domain.appendMessage(ticket, TicketSenderType.ADMIN, operatorId, content.trim());
        return detail(ticketId);
    }

    @Override
    @Transactional
    public AdminTicketDetail resolve(Long ticketId, String content, String operatorId) {
        SupportTicketEntity ticket = domain.requireExists(ticketId);
        requireNotClosed(ticket, "标记解决");
        domain.transit(ticket, TicketStatus.RESOLVED, null);
        // 说明可选：对话里说清楚了就只推状态；填了就作为一条客服消息落进消息流
        if (content != null && !content.isBlank()) {
            domain.appendMessage(ticket, TicketSenderType.ADMIN, operatorId, content.trim());
        }
        return detail(ticketId);
    }

    @Override
    @Transactional
    public AdminTicketDetail close(Long ticketId, String reason, String operatorId) {
        SupportTicketEntity ticket = domain.requireExists(ticketId);
        requireNotClosed(ticket, "关闭");
        domain.transit(ticket, TicketStatus.CLOSED, reason.trim());
        // 与用户自行关闭不同，客服关闭**必须留一条消息**：终结别人的诉求时，
        // 用户至少要看到"是谁、为什么"。用户自己关自己的工单则不需要——
        // 那个动作他本人就是全部上下文
        domain.appendMessage(ticket, TicketSenderType.ADMIN, operatorId, "工单已关闭：" + reason.trim());
        return detail(ticketId);
    }

    private LambdaQueryWrapper<SupportTicketEntity> buildWrapper(AdminTicketQuery query,
                                                                 TicketStatus status, TicketCategory category) {
        String keyword = blankToNull(query.getKeyword());
        return new LambdaQueryWrapper<SupportTicketEntity>()
                .eq(status != null, SupportTicketEntity::getStatus, status == null ? null : status.name())
                .eq(category != null, SupportTicketEntity::getCategory, category == null ? null : category.name())
                .eq(blankToNull(query.getUserId()) != null,
                        SupportTicketEntity::getUserId, query.getUserId())
                // 三个 like 必须**包在一个 and 里**：不包的话它们会以 or 的形式
                // 接到外层条件后面，把状态筛选短路掉——筛出来的比要的多，而没人会发现
                .and(keyword != null, w -> w.like(SupportTicketEntity::getTicketNo, keyword)
                        .or().like(SupportTicketEntity::getTitle, keyword)
                        .or().like(SupportTicketEntity::getOrderNo, keyword))
                // 「看向客服的」= 最后一条消息来自用户。客服上班第一件事是捞欠账，
                // 而不是逐页翻
                .eq(Boolean.TRUE.equals(query.getAwaitingAdmin()),
                        SupportTicketEntity::getLastReplyBy, TicketSenderType.USER.name())
                .ne(Boolean.FALSE.equals(query.getAwaitingAdmin()),
                        SupportTicketEntity::getStatus, TicketStatus.CLOSED.name())
                .ge(query.getCreatedFrom() != null, SupportTicketEntity::getCreatedAt, query.getCreatedFrom())
                .le(query.getCreatedTo() != null, SupportTicketEntity::getCreatedAt, query.getCreatedTo())
                .orderByDesc(SupportTicketEntity::getUpdatedAt)
                .orderByDesc(SupportTicketEntity::getId);
    }

    private void requireNotClosed(SupportTicketEntity ticket, String action) {
        if (TicketStatus.parse(ticket.getStatus()).isTerminal()) {
            throw new IllegalStateException("工单已关闭，不能再" + action);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
