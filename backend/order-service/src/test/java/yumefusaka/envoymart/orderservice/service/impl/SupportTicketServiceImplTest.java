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
import yumefusaka.envoymart.orderservice.mapper.OrderMapper;
import yumefusaka.envoymart.orderservice.mapper.SupportTicketMapper;
import yumefusaka.envoymart.orderservice.mapper.SupportTicketMessageMapper;
import yumefusaka.envoymart.orderservice.model.TicketDetailResponse;
import yumefusaka.envoymart.orderservice.model.TicketSenderType;
import yumefusaka.envoymart.orderservice.model.TicketStatus;
import yumefusaka.envoymart.orderservice.service.TicketNotifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户侧工单入口的写路径。
 * <p>
 * 这里钉的两件事都不会报错，只会到某天才被用户发现：
 * <ul>
 *   <li><b>不带说明重开不推球权</b> —— 工单回到处理中，但球权留在客服侧，
 *       于是它不在客服的「待回复」队列里。用户以为重开即已送达，客服那边看不见它，
 *       两边都以为对方在处理；</li>
 *   <li><b>用户侧的回执里带着客服的账号 id</b> —— 那是个用不上的内部标识，
 *       却给了「哪个客服处理过哪些工单」一个现成的关联键。</li>
 * </ul>
 * 依赖是真的 {@link TicketDomainServiceImpl}（配 mock 的 mapper），不是 mock 出来的域服务 ——
 * 否则跨两步的动作在测试里什么都不做，断言就变成了自问自答。
 */
class SupportTicketServiceImplTest {

    private SupportTicketMapper ticketMapper;
    private SupportTicketMessageMapper messageMapper;
    private SupportTicketServiceImpl service;

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
        service = new SupportTicketServiceImpl(ticketMapper, mock(OrderMapper.class),
                new TicketDomainServiceImpl(ticketMapper, messageMapper, mock(TicketNotifier.class)),
                mock(TicketNotifier.class));
    }

    // ==================== 重开 ====================

    @Test
    void 不带说明重开也要把球权推回用户侧() {
        when(ticketMapper.selectOne(any())).thenReturn(ticket(7L, TicketStatus.RESOLVED));
        when(ticketMapper.update(isNull(), any())).thenReturn(1);

        TicketDetailResponse detail = service.reopen("u1001", 7L, null);

        assertThat(detail.getTicket().getStatus()).isEqualTo(TicketStatus.PROCESSING.name());
        assertThat(capturedUpdates())
                .as("球权没推回用户侧，工单就不在客服的待回复队列里 —— 一条不出声的工单")
                .anySatisfy(w -> assertThat(w.getSqlSet()).contains("last_reply_by"));
        verify(messageMapper, never()).insert(any(SupportTicketMessageEntity.class));
    }

    @Test
    void 带说明重开会落一条用户消息() {
        when(ticketMapper.selectOne(any())).thenReturn(ticket(7L, TicketStatus.RESOLVED));
        when(ticketMapper.update(isNull(), any())).thenReturn(1);

        service.reopen("u1001", 7L, "  问题又出现了  ");

        ArgumentCaptor<SupportTicketMessageEntity> captor =
                ArgumentCaptor.forClass(SupportTicketMessageEntity.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getSenderType()).isEqualTo(TicketSenderType.USER.name());
        assertThat(captor.getValue().getContent()).as("首尾空白要去掉").isEqualTo("问题又出现了");
    }

    @Test
    void 不是已解决的工单不能重开() {
        when(ticketMapper.selectOne(any())).thenReturn(ticket(7L, TicketStatus.PROCESSING));

        // 对一条还没人处理过的工单说"重开"，它本来就是开着的
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.reopen("u1001", 7L, "又出问题了"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有已解决");
        verify(ticketMapper, never()).update(isNull(), any());
    }

    // ==================== 用户侧视图 ====================

    @Test
    void 用户侧的客服消息不带客服的内部账号id() {
        SupportTicketEntity ticket = ticket(7L, TicketStatus.PROCESSING);
        when(ticketMapper.selectOne(any())).thenReturn(ticket);
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message(TicketSenderType.USER, "u1001", "一直没发货"),
                message(TicketSenderType.ADMIN, "u1003", "已联系承运商"),
                message(TicketSenderType.SYSTEM, null, "系统已自动关闭")));

        TicketDetailResponse detail = service.detail("u1001", 7L);

        assertThat(detail.getMessages())
                .extracting(m -> m.getSenderType() + ":" + m.getSenderId())
                .as("用户看「谁说的」只需要知道是客服；内部账号 id 是追责与交接才要的字段")
                .containsExactly("USER:u1001", "ADMIN:null", "SYSTEM:null");
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
        ticket.setCreatedAt(LocalDateTime.now().minusDays(10));
        ticket.setUpdatedAt(LocalDateTime.now().minusDays(10));
        return ticket;
    }

    private SupportTicketMessageEntity message(TicketSenderType type, String senderId, String content) {
        SupportTicketMessageEntity entity = new SupportTicketMessageEntity();
        entity.setId(1L);
        entity.setTicketId(7L);
        entity.setSenderType(type.name());
        entity.setSenderId(senderId);
        entity.setContent(content);
        entity.setCreatedAt(LocalDateTime.now().minusDays(1));
        return entity;
    }

    @SuppressWarnings("unchecked")
    private List<Wrapper<SupportTicketEntity>> capturedUpdates() {
        ArgumentCaptor<Wrapper<SupportTicketEntity>> captor =
                (ArgumentCaptor<Wrapper<SupportTicketEntity>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Wrapper.class);
        verify(ticketMapper, atLeastOnce()).update(isNull(), captor.capture());
        return captor.getAllValues();
    }
}
