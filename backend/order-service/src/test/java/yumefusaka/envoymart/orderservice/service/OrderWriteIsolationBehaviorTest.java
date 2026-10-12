package yumefusaka.envoymart.orderservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import yumefusaka.envoymart.orderservice.client.*;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;
import yumefusaka.envoymart.orderservice.mapper.*;
import yumefusaka.envoymart.orderservice.model.DeliveryStatus;
import yumefusaka.envoymart.orderservice.mq.OrderEventPublisher;
import yumefusaka.envoymart.orderservice.mq.OutboxWriter;
import yumefusaka.envoymart.orderservice.service.impl.OrderDomainServiceImpl;
import yumefusaka.envoymart.orderservice.service.impl.StockLockService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrderWriteIsolationBehaviorTest {
    private OrderMapper orders;
    private ProductClient product;
    private PaymentClient payment;
    private PromotionClient promotion;
    private OrderEventPublisher events;
    private SeataOperationReceiptDecisionProbe probe;
    private OrderDomainServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void 建立外部调用边界() {
        orders = mock(OrderMapper.class); product = mock(ProductClient.class); payment = mock(PaymentClient.class);
        promotion = mock(PromotionClient.class); events = mock(OrderEventPublisher.class);
        probe = mock(SeataOperationReceiptDecisionProbe.class);
        service = new OrderDomainServiceImpl(mock(CartItemMapper.class), orders, mock(OrderItemMapper.class),
                mock(OrderStatusLogMapper.class), mock(OrderDeliveryMapper.class), mock(OrderDeliveryTraceMapper.class),
                mock(AfterSaleMapper.class), product, payment, promotion, mock(StockLockService.class), events,
                mock(OutboxWriter.class), mock(UserTradeGuard.class), mock(ObjectProvider.class), probe);
    }

    @Test
    void 订单全局锁冲突时取消与支付事件均不得触发远端动作() {
        when(orders.lockOwned("u1", 751L)).thenThrow(new IllegalStateException("全局锁冲突"));
        when(orders.lockById(751L)).thenThrow(new IllegalStateException("全局锁冲突"));
        assertThatThrownBy(() -> service.cancelOrder("u1", 751L)).hasMessage("全局锁冲突");
        assertThatThrownBy(() -> service.markPaid(751L)).hasMessage("全局锁冲突");
        assertThatThrownBy(() -> service.closeExpiredOrder(751L)).hasMessage("全局锁冲突");
        assertThatThrownBy(() -> service.shipOrder(751L, "x", "x", "x", "a")).hasMessage("全局锁冲突");
        assertThatThrownBy(() -> service.confirmReceipt("u1", 751L)).hasMessage("全局锁冲突");
        assertThatThrownBy(() -> service.addDeliveryTrace(751L, DeliveryStatus.SIGNED, null, null, null, "a")).hasMessage("全局锁冲突");
        verifyNoInteractions(product, payment, promotion, events, probe);
    }

    @Test
    void 锁已释放但权威决议失败仍不得取消支付或给支付服务返回有效订单() {
        var order = new OrderEntity(); order.setId(751L); order.setUserId("u1"); order.setStatus("CREATED");
        order.setGlobalXid("127.0.0.1:8091:6959");
        when(orders.lockOwned("u1", 751L)).thenReturn(order);
        when(orders.lockById(751L)).thenReturn(order);
        assertThatThrownBy(() -> service.cancelOrder("u1", 751L)).hasMessageContaining("尚未确认");
        assertThatThrownBy(() -> service.markPaid(751L)).hasMessageContaining("尚未确认");
        assertThatThrownBy(() -> service.orderById(751L)).hasMessageContaining("尚未确认");
        verifyNoInteractions(product, payment, promotion, events);
        verify(orders, never()).update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
