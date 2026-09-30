package yumefusaka.envoymart.orderservice.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import yumefusaka.envoymart.orderservice.entity.SupportTicketEntity;
import yumefusaka.envoymart.orderservice.entity.SupportTicketMessageEntity;
import yumefusaka.envoymart.orderservice.mapper.SupportTicketMapper;
import yumefusaka.envoymart.orderservice.mapper.SupportTicketMessageMapper;
import yumefusaka.envoymart.orderservice.model.TicketMessageView;
import yumefusaka.envoymart.orderservice.model.TicketSenderType;
import yumefusaka.envoymart.orderservice.model.TicketStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单域的写路径约束。
 * <p>
 * 这里钉的四件事错了都不会报错，只会到某天才暴露：
 * <ul>
 *   <li><b>状态转移不带前置条件</b> —— 两个人同时点「标记解决」和「关闭」时，
 *       后写的把先写的覆盖掉，两边的响应都是成功，而流水里只剩一个结果；</li>
 *   <li><b>重开不清解决时间</b> —— 下一轮超时任务会拿旧时间戳判定，重开的工单当场被扫走；</li>
 *   <li><b>越权看到别人的工单</b> —— 凭 id 猜到别人的工单必须返回"不存在"，
 *       而不是"无权查看"（后者等于确认这条工单存在）；</li>
 *   <li><b>自动关闭给已被用户确认的工单补一条"系统已关闭"</b> —— 用户手快先确认了，
 *       系统再补一刀，消息流里就出现了两条都能解释"为什么关闭"的记录。</li>
 * </ul>
 */
class TicketDomainServiceImplTest {

    private SupportTicketMapper ticketMapper;
    private SupportTicketMessageMapper messageMapper;
    private TicketDomainServiceImpl domain;

    /**
     * {@code LambdaQueryWrapper} 按 getter 反查列名，靠的是 MyBatis-Plus 的实体元数据缓存——
     * 那份缓存平时由 Spring 容器启动时建立，纯单元测试里没有。
     */
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
        domain = new TicketDomainServiceImpl(ticketMapper, messageMapper);
    }

    // ==================== 状态转移 ====================

    @Test
    void 状态转移用条件更新带上前置状态() {
        when(ticketMapper.update(isNull(), any())).thenReturn(1);
        SupportTicketEntity ticket = ticket(7L, TicketStatus.OPEN);

        domain.transit(ticket, TicketStatus.PROCESSING, null);

        Wrapper<SupportTicketEntity> wrapper = capturedUpdate(1);
        assertThat(wrapper.getSqlSegment())
                .as("WHERE 里没有前置状态，两个并发操作会互相覆盖，而两边都以为自己成功了")
                .contains("status");
        assertThat(wrapper.getSqlSet()).contains("status");
        assertThat(ticket.getStatus()).as("调用方手里的对象也要跟上，省掉一次回查")
                .isEqualTo(TicketStatus.PROCESSING.name());
    }

    @Test
    void 状态没推动时抛冲突而不是静默成功() {
        // 另一个请求先把它推走了，条件更新影响 0 行
        when(ticketMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> domain.transit(ticket(7L, TicketStatus.OPEN), TicketStatus.PROCESSING, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工单状态已变更");
    }

    @Test
    void 非法转移在写库之前就被拒() {
        SupportTicketEntity ticket = ticket(7L, TicketStatus.CLOSED);

        assertThatThrownBy(() -> domain.transit(ticket, TicketStatus.OPEN, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已关闭");

        verify(ticketMapper, never()).update(isNull(), any());
    }

    @Test
    void 重开时把解决时间清掉() {
        when(ticketMapper.update(isNull(), any())).thenReturn(1);
        SupportTicketEntity ticket = ticket(7L, TicketStatus.RESOLVED);
        ticket.setResolvedAt(LocalDateTime.now().minusDays(6));

        domain.transit(ticket, TicketStatus.PROCESSING, null);

        assertThat(capturedUpdate(1).getSqlSet())
                .as("留着旧的解决时间，下一轮超时任务会拿它判定，重开的工单当场被扫走")
                .contains("resolved_at");
        assertThat(ticket.getResolvedAt()).isNull();
    }

    @Test
    void 关闭时落原因与关闭时间() {
        when(ticketMapper.update(isNull(), any())).thenReturn(1);

        domain.transit(ticket(7L, TicketStatus.RESOLVED), TicketStatus.CLOSED, "已与用户电话确认");

        Wrapper<SupportTicketEntity> wrapper = capturedUpdate(1);
        assertThat(wrapper.getSqlSet()).contains("closed_at").contains("close_reason");
    }

    // ==================== 归属校验 ====================

    @Test
    void 别人的工单看起来是不存在() {
        when(ticketMapper.selectOne(any())).thenReturn(null);

        // 报"无权查看"等于确认这条工单存在 —— 凭 id 遍历就能数出平台有多少工单
        assertThatThrownBy(() -> domain.requireOwned(7L, "u1001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工单不存在");
    }

    @Test
    void 归属查询把用户条件写进SQL而不是查出来再比() {
        when(ticketMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> domain.requireOwned(7L, "u1001"));

        ArgumentCaptor<Wrapper<SupportTicketEntity>> captor = wrapperCaptor();
        verify(ticketMapper).selectOne(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .as("只在内存里比对归属，等于先把别人的工单读进内存再说 —— 日志、堆转储、"
                        + "以及将来任何一次「顺手加个缓存」都会把它漏出去")
                .contains("user_id");
    }

    // ==================== 消息 ====================

    @Test
    void 用户消息把球权推给客服() {
        when(ticketMapper.update(isNull(), any())).thenReturn(1);
        SupportTicketEntity ticket = ticket(7L, TicketStatus.OPEN);

        TicketMessageView view = domain.appendMessage(ticket, TicketSenderType.USER, "u1001", "还没收到货");

        assertThat(view.getSenderType()).isEqualTo(TicketSenderType.USER.name());
        assertThat(view.getContent()).isEqualTo("还没收到货");
        assertThat(capturedUpdate(1).getSqlSet())
                .as("球权列不更新，客服工作台就分不出「等我处理」和「等用户回复」")
                .contains("last_reply_by");
        assertThat(ticket.getLastReplyBy()).isEqualTo(TicketSenderType.USER.name());
    }

    @Test
    void 系统消息不抢球权() {
        SupportTicketEntity ticket = ticket(7L, TicketStatus.RESOLVED);
        ticket.setLastReplyBy(TicketSenderType.USER.name());

        domain.appendSystemMessage(ticket, "系统已自动关闭");

        // 系统消息只是给用户一个解释，不该让工单看起来"等客服处理"
        assertThat(ticket.getLastReplyBy()).isEqualTo(TicketSenderType.USER.name());
        ArgumentCaptor<SupportTicketMessageEntity> captor =
                ArgumentCaptor.forClass(SupportTicketMessageEntity.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getSenderType()).isEqualTo(TicketSenderType.SYSTEM.name());
        assertThat(captor.getValue().getSenderId()).isNull();
    }

    // ==================== 超时自动关闭 ====================

    @Test
    void 超时关闭会给用户留一条解释() {
        when(ticketMapper.selectList(any())).thenReturn(List.of(ticket(7L, TicketStatus.RESOLVED)));
        when(ticketMapper.update(isNull(), any())).thenReturn(1);

        assertThat(domain.autoCloseExpired(100)).isEqualTo(1);

        ArgumentCaptor<SupportTicketMessageEntity> captor =
                ArgumentCaptor.forClass(SupportTicketMessageEntity.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getContent())
                .as("工单不声不响地变成已关闭，用户只会看到自己的单消失了")
                .contains("自动关闭");
    }

    @Test
    void 已被用户抢先确认的工单不再补系统消息() {
        when(ticketMapper.selectList(any())).thenReturn(List.of(
                ticket(7L, TicketStatus.RESOLVED), ticket(8L, TicketStatus.RESOLVED)));
        // 第一条推得动；第二条在"扫出来"到"处理"的间隙被用户确认了，条件更新 0 行
        when(ticketMapper.update(isNull(), any())).thenReturn(1, 0);

        assertThat(domain.autoCloseExpired(100))
                .as("一个人手快不该让这一批全滚，也不该把这条算成关闭成功")
                .isEqualTo(1);
        verify(messageMapper).insert(any(SupportTicketMessageEntity.class));
    }

    @Test
    void 超时关闭的筛选条件写在SQL里() {
        when(ticketMapper.selectList(any())).thenReturn(List.of());

        assertThat(domain.autoCloseExpired(100)).isZero();

        // 扫的是 RESOLVED + 超期，条件写在 SQL 里而不是查出来再判：
        // 后者会把全库已解决的工单读进内存，而这张表只会越来越大
        ArgumentCaptor<Wrapper<SupportTicketEntity>> captor = wrapperCaptor();
        verify(ticketMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("status").contains("resolved_at");
    }

    // ==================== 辅助 ====================

    private SupportTicketEntity ticket(Long id, TicketStatus status) {
        SupportTicketEntity ticket = new SupportTicketEntity();
        ticket.setId(id);
        ticket.setTicketNo("TK20260930001");
        ticket.setUserId("u1001");
        ticket.setStatus(status.name());
        ticket.setCreatedAt(LocalDateTime.now().minusDays(10));
        ticket.setUpdatedAt(LocalDateTime.now().minusDays(10));
        return ticket;
    }

    private Wrapper<SupportTicketEntity> capturedUpdate(int times) {
        ArgumentCaptor<Wrapper<SupportTicketEntity>> captor = wrapperCaptor();
        verify(ticketMapper, times(times)).update(isNull(), captor.capture());
        return captor.getAllValues().get(times - 1);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper<SupportTicketEntity>> wrapperCaptor() {
        return (ArgumentCaptor<Wrapper<SupportTicketEntity>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(Wrapper.class);
    }
}
