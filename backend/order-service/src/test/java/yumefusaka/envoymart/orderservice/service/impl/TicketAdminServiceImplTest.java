package yumefusaka.envoymart.orderservice.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import yumefusaka.envoymart.orderservice.entity.SupportTicketEntity;
import yumefusaka.envoymart.orderservice.entity.SupportTicketMessageEntity;
import yumefusaka.envoymart.orderservice.mapper.SupportTicketMapper;
import yumefusaka.envoymart.orderservice.mapper.SupportTicketMessageMapper;
import yumefusaka.envoymart.orderservice.model.TicketSenderType;
import yumefusaka.envoymart.orderservice.model.TicketStatus;
import yumefusaka.envoymart.orderservice.model.admin.AdminTicketDetail;
import yumefusaka.envoymart.orderservice.model.admin.AdminTicketQuery;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客服工作台的写路径与查询条件。
 * <p>
 * 依赖是真的 {@link TicketDomainServiceImpl}（配 mock 的 mapper），不是 mock 出来的域服务 ——
 * 否则「回复即接手」这类<b>跨两步的动作</b>在测试里什么都不做，断言就变成了自问自答。
 */
class TicketAdminServiceImplTest {

    private static final String OPERATOR = "u1003";

    private SupportTicketMapper ticketMapper;
    private SupportTicketMessageMapper messageMapper;
    private TicketAdminServiceImpl service;

    @BeforeAll
    static void initMybatisPlusMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(SupportTicketEntity.class, SupportTicketMessageEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        ticketMapper = mock(SupportTicketMapper.class);
        messageMapper = mock(SupportTicketMessageMapper.class);
        when(messageMapper.selectList(any())).thenReturn(List.of());
        service = new TicketAdminServiceImpl(ticketMapper,
                new TicketDomainServiceImpl(ticketMapper, messageMapper));
    }

    // ==================== 回复 ====================

    @Test
    void 回复待处理的工单即接手() {
        when(ticketMapper.selectById(7L)).thenReturn(ticket(7L, TicketStatus.OPEN));
        when(ticketMapper.update(isNull(), any())).thenReturn(1);

        AdminTicketDetail detail = service.reply(7L, "  已联系承运商  ", OPERATOR);

        assertThat(detail.getTicket().getStatus())
                .as("让客服先点「接手」再发言是两步操作一个意图；照原样返回，"
                        + "工单在列表上显示的还是「待处理」，别的客服会以为没人管")
                .isEqualTo(TicketStatus.PROCESSING.name());

        ArgumentCaptor<SupportTicketMessageEntity> captor =
                ArgumentCaptor.forClass(SupportTicketMessageEntity.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getSenderType()).isEqualTo(TicketSenderType.ADMIN.name());
        assertThat(captor.getValue().getSenderId()).isEqualTo(OPERATOR);
        assertThat(captor.getValue().getContent()).as("首尾空白要去掉，页面上不该出现缩进的客服回复")
                .isEqualTo("已联系承运商");
    }

    @Test
    void 回复处理中的工单不重复接手() {
        when(ticketMapper.selectById(7L)).thenReturn(ticket(7L, TicketStatus.PROCESSING));
        when(ticketMapper.update(isNull(), any())).thenReturn(1);

        AdminTicketDetail detail = service.reply(7L, "在路上", OPERATOR);

        // 只有一次 insert：状态转移没被再走一遍
        verify(messageMapper).insert(any(SupportTicketMessageEntity.class));
        assertThat(detail.getTicket().getStatus()).isEqualTo(TicketStatus.PROCESSING.name());
    }

    @Test
    void 已关闭的工单不能再回复() {
        when(ticketMapper.selectById(7L)).thenReturn(ticket(7L, TicketStatus.CLOSED));

        assertThatThrownBy(() -> service.reply(7L, "再看看", OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已关闭");
    }

    // ==================== 解决 ====================

    @Test
    void 标记解决可以不带说明() {
        when(ticketMapper.selectById(7L)).thenReturn(ticket(7L, TicketStatus.PROCESSING));
        when(ticketMapper.update(isNull(), any())).thenReturn(1);

        AdminTicketDetail detail = service.resolve(7L, null, OPERATOR);

        assertThat(detail.getTicket().getStatus()).isEqualTo(TicketStatus.RESOLVED.name());
        assertThat(detail.getTicket().getResolvedAt())
                .as("解决时间是 7 天确认超时的起算点，不落它这条工单永远不会被自动关闭")
                .isNotNull();
    }

    @Test
    void 标记解决带说明时落一条客服消息() {
        when(ticketMapper.selectById(7L)).thenReturn(ticket(7L, TicketStatus.PROCESSING));
        when(ticketMapper.update(isNull(), any())).thenReturn(1);

        service.resolve(7L, "已补发，运单号 SF123", OPERATOR);

        ArgumentCaptor<SupportTicketMessageEntity> captor =
                ArgumentCaptor.forClass(SupportTicketMessageEntity.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("已补发，运单号 SF123");
    }

    // ==================== 关闭 ====================

    @Test
    void 客服关闭必须留下是谁关的为什么关() {
        when(ticketMapper.selectById(7L)).thenReturn(ticket(7L, TicketStatus.PROCESSING));
        when(ticketMapper.update(isNull(), any())).thenReturn(1);

        AdminTicketDetail detail = service.close(7L, "同一问题重复提交", OPERATOR);

        assertThat(detail.getCloseReason()).isEqualTo("同一问题重复提交");

        ArgumentCaptor<SupportTicketMessageEntity> captor =
                ArgumentCaptor.forClass(SupportTicketMessageEntity.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getSenderType()).isEqualTo(TicketSenderType.ADMIN.name());
        assertThat(captor.getValue().getContent())
                .as("用户是自己点进来的，看不到任何管理后台的日志 —— 原因不落在消息流里就等于没写")
                .contains("同一问题重复提交");
    }

    // ==================== 查询条件 ====================

    @Test
    void 关键词与状态筛选必须同时生效() {
        when(ticketMapper.selectPage(any(), any())).thenReturn(new Page<>());

        AdminTicketQuery query = new AdminTicketQuery();
        query.setKeyword("SF123");
        query.setStatus(TicketStatus.OPEN.name());
        service.list(query);

        String sql = capturedPageWrapper().getSqlSegment();
        assertThat(hasTopLevelOr(sql))
                .as("关键词那组没被整体括起来，最外层出现了 OR，状态筛选全被短路：%s", sql)
                .isFalse();
        assertThat(sql).contains("status").contains("title");
    }

    @Test
    void 只看等客服回复的筛选对应球权列() {
        when(ticketMapper.selectPage(any(), any())).thenReturn(new Page<>());

        AdminTicketQuery query = new AdminTicketQuery();
        query.setAwaitingAdmin(true);
        service.list(query);

        // 客服上班第一件事是捞自己的欠账，而不是从第一页翻到最后
        assertThat(capturedPageWrapper().getSqlSegment()).contains("last_reply_by");
    }

    @Test
    void 非法状态取值被拒而不是静默返回全部() {
        // 拼错的状态值被忽略时，页面会一本正经地展示"全部工单"，
        // 而看的人以为自己是筛过的
        AdminTicketQuery query = new AdminTicketQuery();
        query.setStatus("WAITING");

        assertThatThrownBy(() -> service.list(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WAITING");
    }

    @Test
    void 页码从0开始对外一致() {
        Page<SupportTicketEntity> page = new Page<>(1, 20);
        page.setTotal(3);
        page.setRecords(List.of(ticket(7L, TicketStatus.OPEN)));
        when(ticketMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.list(new AdminTicketQuery());

        assertThat(result.getPage()).isZero();
        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getUserId())
                .as("工作台上要能看到是谁提的 —— 同一个人反复来单说明问题不在单据上")
                .isEqualTo("u1001");
    }

    // ==================== 辅助 ====================

    private SupportTicketEntity ticket(Long id, TicketStatus status) {
        SupportTicketEntity ticket = new SupportTicketEntity();
        ticket.setId(id);
        ticket.setTicketNo("TK20260930001");
        ticket.setUserId("u1001");
        ticket.setCategory("ORDER");
        ticket.setTitle("一直没发货");
        ticket.setStatus(status.name());
        ticket.setCreatedAt(LocalDateTime.now().minusDays(1));
        ticket.setUpdatedAt(LocalDateTime.now().minusDays(1));
        return ticket;
    }

    @SuppressWarnings("unchecked")
    private Wrapper<SupportTicketEntity> capturedPageWrapper() {
        ArgumentCaptor<Wrapper<SupportTicketEntity>> captor =
                (ArgumentCaptor<Wrapper<SupportTicketEntity>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Wrapper.class);
        verify(ticketMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    /** SQL 里有没有出现在<b>最外层</b>的 {@code OR}；{@code order_no} 里的 or 不算。 */
    private static boolean hasTopLevelOr(String sql) {
        int depth = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && sql.regionMatches(true, i, "or", 0, 2)
                    && i > 0 && !Character.isLetterOrDigit(sql.charAt(i - 1))
                    && (i + 2 >= sql.length() || !Character.isLetterOrDigit(sql.charAt(i + 2)))) {
                return true;
            }
        }
        return false;
    }
}
