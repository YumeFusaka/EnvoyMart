package yumefusaka.envoymart.orderservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import tools.jackson.databind.json.JsonMapper;
import yumefusaka.envoymart.common.operation.*;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.orderservice.mapper.OperationReceiptMapper;
import yumefusaka.envoymart.orderservice.model.*;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentWriteFacadeTest {
    @Configuration
    @EnableTransactionManagement
    static class Transactions { }
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private AgentWriteFacade facade;
    private OperationReceiptService receipts;
    private CartService cart;
    private OrderDomainService orders;
    private AfterSaleService afterSales;
    private SupportTicketService tickets;

    @BeforeEach
    void 使用真实映射器与事务() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:facade_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table order_operation_receipt(id bigint auto_increment primary key,user_id varchar(32),operation_id varchar(256),tool_name varchar(64),parameters_hash char(64),global_xid varchar(128),decision_mark varchar(24),hash_version smallint,receipt_version smallint,response_json json,created_at timestamp,completed_at timestamp,unique(user_id,operation_id))");
        jdbc.execute("create table effect(id int primary key,quantity int)");
        jdbc.update("insert into effect values(1,0)");
        var config = new org.apache.ibatis.session.Configuration(new Environment("test", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(OperationReceiptMapper.class);
        var mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(OperationReceiptMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(OperationReceiptService.class, () -> new OperationReceiptService(mapper, JsonMapper.builder().findAndAddModules().build()));
        context.refresh();
        receipts = context.getBean(OperationReceiptService.class);
        cart = mock(CartService.class);
        when(cart.add(eq("u1"), any())).thenAnswer(invocation -> {
            jdbc.update("update effect set quantity=quantity+1 where id=1");
            return CartItemResponse.builder().id(1L).quantity(jdbc.queryForObject("select quantity from effect where id=1", Integer.class)).build();
        });
        orders = mock(OrderDomainService.class); afterSales = mock(AfterSaleService.class); tickets = mock(SupportTicketService.class);
        facade = new AgentWriteFacade(receipts, cart, orders, afterSales, tickets);
    }

    @org.junit.jupiter.api.AfterEach
    void 释放连接上下文() { context.close(); }

    private AddCartItemRequest request() { var req = new AddCartItemRequest(); req.setSkuId(7L); req.setQuantity(1); return req; }
    private OperationContext operation() { return OperationContext.of("u1", "original:step-1", "cart_add", Map.of("skuId", 7, "quantity", 1)); }

    @Test
    void 八并发同操作只写一次跨服务实例重放首次JSON() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<OperationReceiptReply<CartItemResponse>>> calls = java.util.stream.IntStream.range(0,8)
                    .mapToObj(i -> (Callable<OperationReceiptReply<CartItemResponse>>) () -> facade.addCart(operation(), request())).toList();
            for (var future : pool.invokeAll(calls)) assertThat(future.get().body().getData().getQuantity()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select quantity from effect", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_operation_receipt", Integer.class)).isEqualTo(1);
        verify(cart, times(1)).add(eq("u1"), any());
        jdbc.update("update effect set quantity=9");
        var snapshot = receipts.replay(operation(), CartItemResponse.class).orElseThrow();
        assertThat(snapshot.body().getData().getQuantity()).isEqualTo(1);
        assertThat(snapshot.response().getHeaders().getFirst(OperationReceiptMetadata.REPLAYED_HEADER)).isEqualTo("true");
    }

    @Test
    void 领域写后抛错认领和领域一起回滚() {
        doAnswer(invocation -> {
            jdbc.update("update effect set quantity=5");
            throw new IllegalStateException("模拟领域失败");
        }).when(cart).add(eq("u1"), any());
        assertThatThrownBy(() -> facade.addCart(operation(), request())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select quantity from effect", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_operation_receipt", Integer.class)).isZero();
    }

    @Test
    void 同编号换参数拒绝且删除成功允许空结果() {
        facade.addCart(operation(), request());
        assertThatThrownBy(() -> facade.addCart(OperationContext.of("u1", "original:step-1", "cart_add", Map.of("skuId",7,"quantity",2)), request()))
                .isInstanceOf(OperationConflictException.class);
        var remove = OperationContext.of("u1", "remove", "cart_remove", Map.of("cartItemId", 1));
        assertThat(facade.removeCart(remove, 1L).body().getData()).isNull();
        assertThat(facade.removeCart(remove, 1L).replayed()).isTrue();
        verify(cart, times(1)).remove("u1", 1L);
    }

    @Test
    void 修改取消售后与工单均回放首次类型不再次调用领域服务() {
        var update = new UpdateCartItemRequest(); update.setQuantity(2);
        when(cart.updateQuantity("u1",1L,update)).thenReturn(CartItemResponse.builder().id(1L).quantity(2).build());
        var updateOp = OperationContext.of("u1","update","cart_update",Map.of("cartItemId",1L,"quantity",2));
        facade.updateCart(updateOp,1L,update); facade.updateCart(updateOp,1L,update);
        verify(cart,times(1)).updateQuantity("u1",1L,update);
        when(orders.cancelOrder("u1",1L)).thenReturn(yumefusaka.envoymart.contract.OrderResponse.builder().id(1L).status("REFUNDING").build());
        var cancelOp = OperationContext.of("u1","cancel","order_cancel",Map.of("orderId","1"));
        facade.cancel(cancelOp,"1");
        assertThat(facade.cancel(cancelOp,"1").body().getData().getStatus()).isEqualTo("REFUNDING");
        verify(orders,times(1)).cancelOrder("u1",1L);
        var afterReq = new ApplyAfterSaleRequest(); afterReq.setOrderItemId(1L); afterReq.setType("RETURN_REFUND"); afterReq.setReason("不需要");
        when(afterSales.apply("u1",afterReq)).thenReturn(AfterSaleResponse.builder().id(3L).docRef("政策-1").build());
        var afterOp = OperationContext.of("u1","after","after_sale_apply",AgentWriteParameters.afterSale(afterReq));
        facade.afterSale(afterOp,afterReq);
        assertThat(facade.afterSale(afterOp,afterReq).body().getData().getDocRef()).isEqualTo("政策-1");
        verify(afterSales,times(1)).apply("u1",afterReq);
        var ticketReq = new CreateTicketRequest(); ticketReq.setCategory("OTHER"); ticketReq.setTitle("问题"); ticketReq.setContent("描述");
        when(tickets.create("u1",ticketReq)).thenReturn(TicketDetailResponse.builder()
                .ticket(TicketResponse.builder().id(4L).status("OPEN").build())
                .messages(List.of(TicketMessageView.builder().id(1L).content("描述").createdAt(java.time.LocalDateTime.of(2026,10,11,10,0)).build())).build());
        var ticketOp = OperationContext.of("u1","ticket","ticket_create",AgentWriteParameters.ticket(ticketReq));
        facade.ticket(ticketOp,ticketReq);
        assertThat(facade.ticket(ticketOp,ticketReq).body().getData().getMessages().getFirst().getContent()).isEqualTo("描述");
        verify(tickets,times(1)).create("u1",ticketReq);
    }

    @Test
    void 下单回执必须在原有全局边界内创建门面不额外开事务() {
        var request = new CheckoutRequest();
        var operation = OperationContext.of("u1","checkout","cart_checkout",Map.of("receiverName","本人"));
        when(orders.checkoutWithOperation("u1",request,operation)).thenAnswer(invocation -> receipts.commit(operation,
                yumefusaka.envoymart.contract.OrderResponse.class, () -> yumefusaka.envoymart.contract.OrderResponse.builder().id(6L).status("CREATED").build()));
        facade.checkout(operation,request); facade.checkout(operation,request);
        verify(orders,times(1)).checkoutWithOperation("u1",request,operation);
        verify(orders,never()).checkout(any(),any());
    }

    @Test
    void 回执版本不匹配不能再次写业务() {
        facade.addCart(operation(),request());
        jdbc.update("update order_operation_receipt set receipt_version=2");
        assertThatThrownBy(() -> facade.addCart(operation(),request())).isInstanceOf(OperationReceiptUnavailableException.class);
        verify(cart,times(1)).add(eq("u1"),any());
    }

    @Test
    void 回执完成更新失败必须回滚业务和认领() {
        doAnswer(invocation -> {
            jdbc.update("update effect set quantity=1");
            jdbc.update("delete from order_operation_receipt");
            return CartItemResponse.builder().id(1L).quantity(1).build();
        }).when(cart).add(eq("u1"),any());
        assertThatThrownBy(() -> facade.addCart(operation(),request())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select quantity from effect",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_operation_receipt",Integer.class)).isZero();
    }
}
