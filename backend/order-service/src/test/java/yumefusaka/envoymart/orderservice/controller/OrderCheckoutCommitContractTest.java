package yumefusaka.envoymart.orderservice.controller;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.common.operation.OperationReceiptUnavailableException;
import yumefusaka.envoymart.contract.OrderResponse;
import yumefusaka.envoymart.orderservice.model.CheckoutRequest;
import yumefusaka.envoymart.orderservice.service.AgentWriteFacade;
import yumefusaka.envoymart.orderservice.service.OrderDomainService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrderCheckoutCommitContractTest {
    @Test
    void 普通结算不能把事务内草稿当成已成功订单() {
        var domain = mock(OrderDomainService.class);
        var request = new CheckoutRequest();
        var draft = OrderResponse.builder().id(751L).status("CREATED").build();
        when(domain.checkout("user", request)).thenReturn(draft);
        when(domain.getOrder("user", 751L)).thenThrow(new OperationReceiptUnavailableException("TC未决"));
        var controller = new OrderController(domain, mock(AgentWriteFacade.class));

        assertThatThrownBy(() -> controller.checkout("user", null, request))
                .isInstanceOf(OperationReceiptUnavailableException.class);
    }

    @Test
    void 普通结算在事务代理返回后复读已决议订单() {
        var domain = mock(OrderDomainService.class);
        var request = new CheckoutRequest();
        var draft = OrderResponse.builder().id(100L).status("CREATED").build();
        var committed = OrderResponse.builder().id(100L).status("CREATED").orderNo("已核验订单").build();
        when(domain.checkout("user", request)).thenReturn(draft);
        when(domain.getOrder("user", 100L)).thenReturn(committed);
        var controller = new OrderController(domain, mock(AgentWriteFacade.class));

        var response = controller.checkout("user", null, request);

        assertThat(response.getBody().getData()).isSameAs(committed);
        var sequence = inOrder(domain);
        sequence.verify(domain).checkout("user", request);
        sequence.verify(domain).getOrder("user", 100L);
    }
}
