package yumefusaka.envoymart.orderservice.mq;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import yumefusaka.envoymart.orderservice.entity.EventOutboxEntity;
import yumefusaka.envoymart.orderservice.mapper.EventOutboxMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxRelayTest {
    @Test
    void broker拒绝确认不能把发件箱标为成功() {
        var mapper = mock(EventOutboxMapper.class);
        var rabbit = mock(RabbitTemplate.class);
        var row = row();
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.lockPending(1L)).thenReturn(row);
        when(mapper.markFailed(eq(1L), anyString())).thenReturn(1);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "模拟broker拒绝"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(), any(CorrelationData.class));

        new OutboxRelay(mapper, new OutboxDispatchTransaction(mapper, rabbit)).relay();

        verify(mapper, never()).markSent(anyLong());
        verify(mapper).markFailed(eq(1L), contains("确认"));
    }

    @Test
    void 事务屏障失败不能在调度器外层更新原行() {
        var mapper = mock(EventOutboxMapper.class);
        var dispatch = mock(OutboxDispatchTransaction.class);
        when(mapper.selectList(any())).thenReturn(List.of(row()));
        when(dispatch.dispatch(1L)).thenThrow(new IllegalStateException("模拟全局事务未决"));

        new OutboxRelay(mapper, dispatch).relay();

        verify(mapper, never()).markFailed(anyLong(), anyString());
        verify(mapper, never()).markSent(anyLong());
        verify(mapper, never()).updateById(any(EventOutboxEntity.class));
    }

    @Test
    void 行锁核验失败在调用broker和任意更新之前退出() {
        var mapper = mock(EventOutboxMapper.class);
        var rabbit = mock(RabbitTemplate.class);
        when(mapper.lockPending(1L)).thenThrow(new org.springframework.dao.CannotAcquireLockException("模拟全局锁未释放"));

        assertThatThrownBy(() -> new OutboxDispatchTransaction(mapper, rabbit).dispatch(1L))
                .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
        verifyNoInteractions(rabbit);
        verify(mapper, never()).markSent(anyLong());
        verify(mapper, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void broker成功后数据库标记失败不能转成已投递或额外修改失败状态() {
        var mapper = mock(EventOutboxMapper.class);
        var rabbit = mock(RabbitTemplate.class);
        when(mapper.lockPending(1L)).thenReturn(row());
        when(mapper.markSent(1L)).thenThrow(new IllegalStateException("模拟数据库提交链路故障"));
        doAnswer(call -> {
            ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(), any(CorrelationData.class));

        assertThatThrownBy(() -> new OutboxDispatchTransaction(mapper, rabbit).dispatch(1L)).isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).markFailed(anyLong(), anyString());
    }

    static EventOutboxEntity row() {
        var row = new EventOutboxEntity();
        row.setId(1L); row.setEventType("order.created"); row.setAggregateId("独立订单");
        row.setExchangeName("envoymart.order"); row.setRoutingKey("order.created");
        row.setPayload("{\"orderId\":1}"); row.setAttempts(0);
        return row;
    }
}
