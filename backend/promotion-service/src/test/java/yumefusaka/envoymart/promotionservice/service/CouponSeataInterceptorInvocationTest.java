package yumefusaka.envoymart.promotionservice.service;

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
import yumefusaka.envoymart.promotionservice.mapper.CouponMapper;
import yumefusaka.envoymart.promotionservice.mapper.UserCouponMapper;
import yumefusaka.envoymart.promotionservice.service.impl.CouponServiceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CouponSeataInterceptorInvocationTest {
    @org.junit.jupiter.api.BeforeAll
    static void 初始化Seata实际配置提供器所需Spring环境() {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("seata.config.type", "file")
                .withProperty("seata.config.file.name", "file.conf")
                .withProperty("seata.registry.type", "file");
        org.apache.seata.common.holder.ObjectHolder.INSTANCE.setObject("springConfigurableEnvironment", environment);
    }

    @Test
    void 营销外层回执认领取连接和提交均保持全局锁() throws Exception {
        var rows = mock(yumefusaka.envoymart.promotionservice.mapper.OperationReceiptMapper.class);
        when(rows.complete(any(), any(), any())).thenReturn(1);
        var target = new OperationReceiptService(rows, tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build());
        var source = new DriverManagerDataSource("jdbc:h2:mem:promotionreceiptaop_" + java.util.UUID.randomUUID(), "sa", "");
        var transactions = new DataSourceTransactionManager(source) {
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
                assertThat(RootContext.requireGlobalLock()).isTrue(); super.doBegin(transaction, definition);
            }
            @Override protected void doCommit(DefaultTransactionStatus status) {
                assertThat(RootContext.requireGlobalLock()).isTrue(); super.doCommit(status);
            }
        };
        var parser = new io.seata.integration.tx.api.interceptor.parser.GlobalTransactionalInterceptorParser();
        var handler = parser.parserInterfaceToProxy(target, "promotionReceipts");
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        if (handler != null) factory.addAdvice(new AdapterSpringSeataInterceptor(handler));
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        ((OperationReceiptService) factory.getProxy()).commit(
                yumefusaka.envoymart.common.operation.OperationContext.of("u1", "receive-1", "coupon_receive", java.util.Map.of("couponId", 1)),
                String.class, () -> { assertThat(RootContext.requireGlobalLock()).isTrue(); return "已领取"; });
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }

    @Test
    void 本地退券真实拦截器覆盖取连接和提交() throws Exception {
        var coupons = mock(UserCouponMapper.class);
        when(coupons.unredeem(1L, "u1", "o1")).thenAnswer(invocation -> { assertThat(RootContext.requireGlobalLock()).isTrue(); return 0; });
        var target = new CouponServiceImpl(mock(CouponMapper.class), coupons);
        var source = new DriverManagerDataSource("jdbc:h2:mem:couponaop_" + java.util.UUID.randomUUID(), "sa", "");
        var transactions = new DataSourceTransactionManager(source) {
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
                assertThat(RootContext.requireGlobalLock()).isTrue(); super.doBegin(transaction, definition);
            }
            @Override protected void doCommit(DefaultTransactionStatus status) {
                assertThat(RootContext.requireGlobalLock()).isTrue(); super.doCommit(status);
            }
        };
        var parser = new io.seata.integration.tx.api.interceptor.parser.GlobalTransactionalInterceptorParser();
        var handler = parser.parserInterfaceToProxy(target, "couponService");
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        if (handler != null) factory.addAdvice(new AdapterSpringSeataInterceptor(handler));
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        ((CouponServiceImpl) factory.getProxy()).unredeem("u1", 1L, "o1");
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }
}
