package yumefusaka.envoymart.orderservice.service;

import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrderGlobalDecisionTest {
    @AfterEach
    void 还原线程事务上下文() { RootContext.unbind(); }

    private OrderEntity order(String xid) {
        var order = new OrderEntity(); order.setId(751L); order.setGlobalXid(xid); return order;
    }

    @Test
    void 本地存量兼容而创建事务内部可返回自己的草稿() {
        var probe = mock(SeataOperationReceiptDecisionProbe.class);
        assertThatCode(() -> OrderGlobalDecision.requireCommitted(order(null), probe)).doesNotThrowAnyException();
        RootContext.bind("127.0.0.1:8091:42");
        assertThatCode(() -> OrderGlobalDecision.requireCommitted(order(RootContext.getXID()), probe)).doesNotThrowAnyException();
        verifyNoInteractions(probe);
    }

    @Test
    void 未决与回滚失败残留订单一律拒绝不能靠锁释放猜成功() {
        var probe = mock(SeataOperationReceiptDecisionProbe.class);
        when(probe.committed("127.0.0.1:8091:42")).thenReturn(false);
        assertThatThrownBy(() -> OrderGlobalDecision.requireCommitted(order("127.0.0.1:8091:42"), probe))
                .isInstanceOf(yumefusaka.envoymart.common.operation.OperationReceiptUnavailableException.class);
        verify(probe).committed("127.0.0.1:8091:42");
    }

    @Test
    void 另一事务只能读取已权威确认成功的订单() {
        var probe = mock(SeataOperationReceiptDecisionProbe.class);
        RootContext.bind("127.0.0.1:8091:99");
        when(probe.committed("127.0.0.1:8091:42")).thenReturn(true);
        assertThatCode(() -> OrderGlobalDecision.requireCommitted(order("127.0.0.1:8091:42"), probe)).doesNotThrowAnyException();
        verify(probe).committed("127.0.0.1:8091:42");
    }
}
