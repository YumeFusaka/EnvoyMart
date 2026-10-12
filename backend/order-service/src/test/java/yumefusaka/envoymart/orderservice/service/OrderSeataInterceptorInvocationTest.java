package yumefusaka.envoymart.orderservice.service;

import org.apache.seata.core.context.RootContext;
import org.apache.seata.spring.annotation.AdapterSpringSeataInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.DefaultTransactionStatus;
import yumefusaka.envoymart.orderservice.client.*;
import yumefusaka.envoymart.orderservice.mapper.*;
import yumefusaka.envoymart.orderservice.mq.*;
import yumefusaka.envoymart.orderservice.service.impl.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 验证实际Seata parser/adapter及Spring事务顺序；不以H2冒充AT或TC运行证明。 */
class OrderSeataInterceptorInvocationTest {
    @io.seata.spring.annotation.GlobalLock
    public static class TypeOnlyLockProbe {
        @io.seata.spring.annotation.GlobalTransactional
        public void globalEntry() { }
        @org.springframework.transaction.annotation.Transactional
        public boolean localEntry() { return RootContext.requireGlobalLock(); }
    }

    @Test
    void 真实Seata按方法名单跳过仅有类级锁的本地入口() throws Exception {
        var target = new TypeOnlyLockProbe();
        var parser = new io.seata.integration.tx.api.interceptor.parser.GlobalTransactionalInterceptorParser();
        var handler = parser.parserInterfaceToProxy(target, "oldTypeOnly");
        assertThat(handler.getMethodsToProxy()).contains("globalEntry").doesNotContain("localEntry");
        var transactions = new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:oldtype_" + UUID.randomUUID(), "sa", "")) {
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
                assertThat(RootContext.requireGlobalLock()).as("旧TYPE声明不会启用标志，复现库真实过滤语义").isFalse();
                super.doBegin(transaction, definition);
            }
        };
        assertThat(proxy(target, transactions).localEntry()).isFalse();
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }

    @org.junit.jupiter.api.BeforeAll
    static void 初始化Seata实际配置提供器所需Spring环境() {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("seata.config.type", "file")
                .withProperty("seata.config.file.name", "file.conf")
                .withProperty("seata.registry.type", "file");
        org.apache.seata.common.holder.ObjectHolder.INSTANCE.setObject("springConfigurableEnvironment", environment);
    }

    @Test
    void 外层回执认领先启用全局锁且嵌套业务返回后提交仍启用() throws Exception {
        var rows = mock(OperationReceiptMapper.class);
        when(rows.complete(any(), any(), any())).thenReturn(1);
        var receipts = new OperationReceiptService(rows, tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build());
        var cartRows = mock(CartItemMapper.class);
        var row = new yumefusaka.envoymart.orderservice.entity.CartItemEntity(); row.setId(1L); row.setUserId("u1");
        when(cartRows.lockOwned("u1", 1L)).thenAnswer(invocation -> { assertThat(RootContext.requireGlobalLock()).isTrue(); return row; });
        var cart = new CartServiceImpl(cartRows, mock(ProductClient.class), mock(UserTradeGuard.class));
        var commits = new AtomicInteger();
        var transactions = new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:receiptaop_" + UUID.randomUUID(), "sa", "")) {
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
                assertThat(RootContext.requireGlobalLock()).as("外层借连接之前必须启用全局锁").isTrue(); super.doBegin(transaction, definition);
            }
            @Override protected void doCommit(DefaultTransactionStatus status) {
                assertThat(RootContext.requireGlobalLock()).as("嵌套业务返回后外层提交仍需全局锁").isTrue();
                commits.incrementAndGet(); super.doCommit(status);
            }
        };
        var cartProxy = proxy(cart, transactions);
        var receiptProxy = proxy(receipts, transactions);
        var operation = yumefusaka.envoymart.common.operation.OperationContext.of("u1", "remove-1", "cart_remove", java.util.Map.of("cartItemId", 1));
        receiptProxy.commit(operation, Void.class, () -> {
            assertThat(RootContext.requireGlobalLock()).isTrue();
            cartProxy.remove("u1", 1L);
            assertThat(RootContext.requireGlobalLock()).as("内层不得解绑外层全局锁标志").isTrue();
            return null;
        });
        assertThat(commits.get()).isEqualTo(1);
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, DataSourceTransactionManager transactions) throws Exception {
        var parser = new io.seata.integration.tx.api.interceptor.parser.GlobalTransactionalInterceptorParser();
        var handler = parser.parserInterfaceToProxy(target, target.getClass().getSimpleName());
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        if (handler != null) factory.addAdvice(new AdapterSpringSeataInterceptor(handler));
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 定时单笔关单在取业务连接之前启用全局锁且提交时仍启用() throws Exception {
        var orders = mock(OrderMapper.class);
        when(orders.lockById(751L)).thenAnswer(invocation -> {
            assertThat(RootContext.requireGlobalLock()).isTrue();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        });
        var target = new OrderDomainServiceImpl(mock(CartItemMapper.class), orders, mock(OrderItemMapper.class),
                mock(OrderStatusLogMapper.class), mock(OrderDeliveryMapper.class), mock(OrderDeliveryTraceMapper.class),
                mock(AfterSaleMapper.class), mock(ProductClient.class), mock(PaymentClient.class), mock(PromotionClient.class),
                mock(StockLockService.class), mock(OrderEventPublisher.class), mock(OutboxWriter.class), mock(UserTradeGuard.class),
                mock(org.springframework.beans.factory.ObjectProvider.class), mock(SeataOperationReceiptDecisionProbe.class));
        var source = new DriverManagerDataSource("jdbc:h2:mem:aop_" + UUID.randomUUID(), "sa", "");
        var commits = new AtomicInteger();
        var transactions = new DataSourceTransactionManager(source) {
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
                assertThat(RootContext.requireGlobalLock()).as("Seata必须先于本地事务取连接").isTrue();
                super.doBegin(transaction, definition);
            }
            @Override protected void doCommit(DefaultTransactionStatus status) {
                assertThat(RootContext.requireGlobalLock()).as("本地提交必须仍处于全局锁上下文").isTrue();
                commits.incrementAndGet(); super.doCommit(status);
            }
        };
        var parser = new io.seata.integration.tx.api.interceptor.parser.GlobalTransactionalInterceptorParser();
        var handler = parser.parserInterfaceToProxy(target, "orderDomainService");
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        if (handler != null) factory.addAdvice(new AdapterSpringSeataInterceptor(handler));
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var proxy = (OrderDomainServiceImpl) factory.getProxy();
        try {
            assertThat(proxy.closeExpiredOrder(751L)).isFalse();
            assertThat(commits.get()).isEqualTo(1);
        } finally { assertThat(RootContext.requireGlobalLock()).as("方法结束恢复原全局锁标志").isFalse(); }
    }
}
