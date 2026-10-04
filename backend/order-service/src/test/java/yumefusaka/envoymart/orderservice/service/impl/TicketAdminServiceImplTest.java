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
import yumefusaka.envoymart.orderservice.service.TicketNotifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                new TicketDomainServiceImpl(ticketMapper, messageMapper, mock(TicketNotifier.class)),
                mock(TicketNotifier.class));
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
        // 准入是一次条件更新，命中 0 行
        when(ticketMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.reply(7L, "再看看", OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已关闭");
        verify(messageMapper, never()).insert(any(SupportTicketMessageEntity.class));
    }

    /**
     * 已解决的工单被客服答复，等于把球又踢回去 —— 必须退回处理中。
     * <p>
     * 不退的话它在 7 天计时里继续躺着，10 分钟内就会被超时任务关掉：用户刚收到一条
     * 客服回复，紧跟着一条「超时未确认，系统已自动关闭」，两句话自相矛盾。
     * <p>
     * 断言落在 SET 子句上而不是 {@code detail} 里的状态：单测的 mapper 是 mock，
     * 读回来的就是传进去的那个实例，只看对象状态分不清「真的发了 UPDATE」和
     * 「transit 只在内存里改了字段」。
     */
    @Test
    void 已解决的工单被客服答复时退回处理中并清掉解决时间() {
        when(ticketMapper.selectById(7L)).thenReturn(ticket(7L, TicketStatus.RESOLVED));
        when(ticketMapper.update(isNull(), any())).thenReturn(1);

        service.reply(7L, "又查了一下，确实是我们漏发", OPERATOR);

        assertThat(capturedUpdates())
                .as("没有任何一步把状态推回 PROCESSING")
                .anySatisfy(w -> assertThat(w.getSqlSet())
                        .contains("status").contains("resolved_at"));
    }

    @Test
    void 处理中的工单回复时不重复转移状态() {
        when(ticketMapper.selectById(7L)).thenReturn(ticket(7L, TicketStatus.PROCESSING));
        when(ticketMapper.update(isNull(), any())).thenReturn(1);

        service.reply(7L, "在路上", OPERATOR);

        // 每次 UPDATE 都只动球权/活跃时间那一组列，没有一次写 status
        assertThat(capturedUpdates())
                .as("已经是处理中，再推一次状态没有意义，还会平白多一次可能失败的并发窗口")
                .noneSatisfy(w -> assertThat(w.getSqlSet()).contains("status"));
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

        // 客服上班第一件事是捞自己的欠账，而不是从第一页翻到最后。
        // 两个条件缺一不可：只看 last_reply_by 会把已关闭的工单也捞出来 ——
        // 那些球的落点早已无人关心，混在欠账队列里只会让客服点开一条死单
        assertThat(capturedPageWrapper().getSqlSegment())
                .contains("last_reply_by").contains("status");
    }

    @Test
    void 球在客服侧的筛选不能漏掉从未有人回复过的工单() {
        when(ticketMapper.selectPage(any(), any())).thenReturn(new Page<>());

        AdminTicketQuery query = new AdminTicketQuery();
        query.setAwaitingAdmin(false);
        service.list(query);

        // 写 ne(USER) 是不够的：SQL 里 NULL 参与比较的结果是 NULL，不是 true，
        // 于是 last_reply_by 还是空（客服一次都没回过）的工单会被整批漏掉
        assertThat(capturedPageWrapper().getSqlSegment())
                .contains("last_reply_by")
                .containsIgnoringCase("is null")
                .contains("status");
    }

    @Test
    void 关键词也搜工单消息正文() {
        when(ticketMapper.selectPage(any(), any())).thenReturn(new Page<>());

        AdminTicketQuery query = new AdminTicketQuery();
        query.setKeyword("漏发");
        service.list(query);

        // 「之前有人提过同样的问题吗」正是把消息拆成独立表时想要的能力
        // （见 SupportTicketMessageEntity 的说明），只用单号/标题搜就把它落了空
        assertThat(capturedPageWrapper().getSqlSegment()).contains("support_ticket_message");
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

    /** 一次动作里发出去的全部 UPDATE；断言落在 SET 子句上，见「已解决的工单被客服答复」的说明 */
    @SuppressWarnings("unchecked")
    private List<Wrapper<SupportTicketEntity>> capturedUpdates() {
        ArgumentCaptor<Wrapper<SupportTicketEntity>> captor =
                (ArgumentCaptor<Wrapper<SupportTicketEntity>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Wrapper.class);
        verify(ticketMapper, atLeastOnce()).update(isNull(), captor.capture());
        return captor.getAllValues();
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
