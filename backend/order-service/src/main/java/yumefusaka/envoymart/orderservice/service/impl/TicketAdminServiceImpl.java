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
import yumefusaka.envoymart.orderservice.service.TicketNotifier;

import java.util.List;

@Service
public class TicketAdminServiceImpl implements TicketAdminService {

    private final SupportTicketMapper ticketMapper;
    private final TicketDomainService domain;
    private final TicketNotifier notifier;

    public TicketAdminServiceImpl(SupportTicketMapper ticketMapper, TicketDomainService domain,
                                  TicketNotifier notifier) {
        this.ticketMapper = ticketMapper;
        this.domain = domain;
        this.notifier = notifier;
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
        domain.requireOpenForConversation(ticket, "回复");
        // 首次回复即接手。让客服先点一下"接手"再发言是两步操作一个意图，
        // 现实中没人那么干——而没接手的工单在列表上仍显示"待处理"，是错的
        TicketStatus current = TicketStatus.parse(ticket.getStatus());
        if (current == TicketStatus.OPEN || current == TicketStatus.RESOLVED) {
            // 已解决的工单被客服答复，同样退回处理中。不这么做的话它在 7 天计时里
            // 继续躺着，10 分钟内就会被超时任务关掉 —— 用户刚收到一条客服回复，
            // 紧跟着一条"超时未确认，系统已自动关闭"，两句话自相矛盾。
            // 回到 PROCESSING 顺带把 resolved_at 清空（transit 里做的），计时重新开始
            domain.transit(ticket, TicketStatus.PROCESSING, null);
        }
        domain.appendMessage(ticket, TicketSenderType.ADMIN, operatorId, content.trim());
        // 回复是这个系统里**最需要被推送**的写动作：用户等客服回话，而不是等一个数字。
        // 推送走与 /tickets/summary 完全相同的判据（未关闭 && 客服已回过话），
        // 所以这里不需要判断"该不该推"—— 重新算一遍就是答案
        notifier.notifyAwaiting(ticket.getUserId());
        return detail(ticketId);
    }

    @Override
    @Transactional
    public AdminTicketDetail resolve(Long ticketId, String content, String operatorId) {
        SupportTicketEntity ticket = domain.requireExists(ticketId);
        domain.requireOpenForConversation(ticket, "标记解决");
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
        domain.requireOpenForConversation(ticket, "关闭");
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
        Boolean awaitingAdmin = query.getAwaitingAdmin();
        return new LambdaQueryWrapper<SupportTicketEntity>()
                .eq(status != null, SupportTicketEntity::getStatus, status == null ? null : status.name())
                .eq(category != null, SupportTicketEntity::getCategory, category == null ? null : category.name())
                .eq(blankToNull(query.getUserId()) != null,
                        SupportTicketEntity::getUserId, query.getUserId())
                // 四个条件必须**包在一个 and 里**：不包的话它们会以 or 的形式
                // 接到外层条件后面，把状态筛选短路掉——筛出来的比要的多，而没人会发现
                .and(keyword != null, w -> w.like(SupportTicketEntity::getTicketNo, keyword)
                        .or().like(SupportTicketEntity::getTitle, keyword)
                        .or().like(SupportTicketEntity::getOrderNo, keyword)
                        // 也搜消息正文：「之前有人提过同样的问题吗」正是客服拆表时
                        // 想要的那个能力（见 SupportTicketMessageEntity 的说明），
                        // 只用单号/标题搜就把它落了空。
                        // {0} 由 MyBatis-Plus 绑成占位符参数，keyword 不进 SQL 文本
                        .or().apply("id in (select ticket_id from support_ticket_message "
                                + "where content like {0})", "%" + keyword + "%"))
                // 「看向客服的」= 球权在用户侧 且 工单还开着（用户等着回复）。
                // 两个取值都要求「未关闭」，它们在未关闭这个集合内互补；已关闭的工单
                // 两边都不出现 —— 关掉的工单没有球权可言，把它算进任何一侧都是噪声。
                // 客服上班第一件事是捞欠账，而不是逐页翻
                .ne(awaitingAdmin != null, SupportTicketEntity::getStatus, TicketStatus.CLOSED.name())
                .eq(Boolean.TRUE.equals(awaitingAdmin),
                        SupportTicketEntity::getLastReplyBy, TicketSenderType.USER.name())
                // 球权不在用户侧：从没有人回复过（last_reply_by 为空）也算客服侧，
                // 不能写成 ne(USER) —— SQL 的 NULL 比较结果是 NULL，那些工单会被漏掉
                .and(Boolean.FALSE.equals(awaitingAdmin), w -> w
                        .isNull(SupportTicketEntity::getLastReplyBy)
                        .or().ne(SupportTicketEntity::getLastReplyBy, TicketSenderType.USER.name()))
                .ge(query.getCreatedFrom() != null, SupportTicketEntity::getCreatedAt, query.getCreatedFrom())
                .le(query.getCreatedTo() != null, SupportTicketEntity::getCreatedAt, query.getCreatedTo())
                .orderByDesc(SupportTicketEntity::getUpdatedAt)
                .orderByDesc(SupportTicketEntity::getId);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
