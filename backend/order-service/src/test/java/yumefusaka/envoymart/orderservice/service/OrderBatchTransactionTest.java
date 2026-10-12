package yumefusaka.envoymart.orderservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import yumefusaka.envoymart.orderservice.client.*;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;
import yumefusaka.envoymart.orderservice.entity.OrderStatusLogEntity;
import yumefusaka.envoymart.orderservice.mapper.*;
import yumefusaka.envoymart.orderservice.mq.*;
import yumefusaka.envoymart.orderservice.service.impl.OrderDomainServiceImpl;
import yumefusaka.envoymart.orderservice.service.impl.StockLockService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderBatchTransactionTest {
    @org.junit.jupiter.api.BeforeAll
    static void 初始化本测试自己的订单映射元数据() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, OrderEntity.class);
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions { }

    @Test
    void 第二笔锁冲突不回滚第一笔已提交订单及状态流水() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:batch_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("create table effect(id bigint primary key,status varchar(24))");
        jdbc.execute("create table status_log(order_id bigint)");
        jdbc.update("insert into effect values(1,'CREATED'),(2,'CREATED')");
        var orders = mock(OrderMapper.class);
        var items = mock(OrderItemMapper.class);
        var logs = mock(OrderStatusLogMapper.class);
        var product = mock(ProductClient.class);
        var promotion = mock(PromotionClient.class);
        var first = due(1L);
        when(orders.selectList(any())).thenReturn(List.of(first, due(2L)));
        when(orders.lockById(1L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jdbc.queryForObject("select id from effect where id=1 for update", Long.class);
            return first;
        });
        when(orders.lockById(2L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            throw new IllegalStateException("第二笔全局锁冲突");
        });
        when(orders.update(any(), any())).thenAnswer(invocation -> jdbc.update("update effect set status='CLOSED' where id=1"));
        when(logs.insert(any(OrderStatusLogEntity.class))).thenAnswer(invocation -> {
            OrderStatusLogEntity row = invocation.getArgument(0);
            return jdbc.update("insert into status_log values(?)", row.getOrderId());
        });
        when(items.selectList(any())).thenReturn(List.of());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
            context.registerBean(OrderDomainServiceImpl.class, () -> new OrderDomainServiceImpl(
                    mock(CartItemMapper.class), orders, items, logs, mock(OrderDeliveryMapper.class),
                    mock(OrderDeliveryTraceMapper.class), mock(AfterSaleMapper.class), product,
                    mock(PaymentClient.class), promotion, mock(StockLockService.class), mock(OrderEventPublisher.class),
                    mock(OutboxWriter.class), mock(UserTradeGuard.class), context.getBeanProvider(OrderDomainServiceImpl.class),
                    mock(SeataOperationReceiptDecisionProbe.class)));
            // 回执仅由checkout使用，批量测试仍显式满足生产必需依赖。
            context.registerBean(OperationReceiptService.class, () -> mock(OperationReceiptService.class));
            context.refresh();
            assertThat(context.getBean(OrderDomainServiceImpl.class).closeExpiredOrders(100)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select status from effect where id=1", String.class)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("select status from effect where id=2", String.class)).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("select count(*) from status_log where order_id=1", Integer.class)).isEqualTo(1);
        verifyNoInteractions(product, promotion);
    }

    private OrderEntity due(Long id) {
        var order = new OrderEntity(); order.setId(id); order.setUserId("u1"); order.setOrderNo("o" + id);
        order.setStatus("CREATED"); order.setExpireAt(LocalDateTime.now().minusMinutes(1)); return order;
    }
}
