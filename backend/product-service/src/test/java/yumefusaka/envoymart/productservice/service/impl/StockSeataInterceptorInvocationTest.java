package yumefusaka.envoymart.productservice.service.impl;

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
import yumefusaka.envoymart.contract.StockChangeRequest;
import yumefusaka.envoymart.productservice.mapper.ProductSkuMapper;
import yumefusaka.envoymart.productservice.mapper.StockLogMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StockSeataInterceptorInvocationTest {
    @org.junit.jupiter.api.BeforeAll
    static void 初始化Seata实际配置提供器所需Spring环境() {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("seata.config.type", "file")
                .withProperty("seata.config.file.name", "file.conf")
                .withProperty("seata.registry.type", "file");
        org.apache.seata.common.holder.ObjectHolder.INSTANCE.setObject("springConfigurableEnvironment", environment);
    }

    @Test
    void 本地库存写的真实拦截器覆盖取连接和提交() throws Exception {
        var sku = mock(ProductSkuMapper.class);
        when(sku.deductStock(1L, 1)).thenAnswer(invocation -> { assertThat(RootContext.requireGlobalLock()).isTrue(); return 1; });
        when(sku.selectStock(1L)).thenReturn(9);
        var entity = new yumefusaka.envoymart.productservice.entity.ProductSkuEntity();
        entity.setId(1L); entity.setSpuId(1L);
        when(sku.selectById(1L)).thenReturn(entity);
        var target = new StockServiceImpl(sku, mock(StockLogMapper.class),
                mock(yumefusaka.envoymart.productservice.projection.ProductProjectionRequests.class));
        var source = new DriverManagerDataSource("jdbc:h2:mem:stockaop_" + java.util.UUID.randomUUID(), "sa", "");
        var transactions = new DataSourceTransactionManager(source) {
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
                assertThat(RootContext.requireGlobalLock()).isTrue(); super.doBegin(transaction, definition);
            }
            @Override protected void doCommit(DefaultTransactionStatus status) {
                assertThat(RootContext.requireGlobalLock()).isTrue(); super.doCommit(status);
            }
        };
        var parser = new io.seata.integration.tx.api.interceptor.parser.GlobalTransactionalInterceptorParser();
        var handler = parser.parserInterfaceToProxy(target, "stockService");
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        if (handler != null) factory.addAdvice(new AdapterSpringSeataInterceptor(handler));
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        ((StockServiceImpl) factory.getProxy()).deduct(StockChangeRequest.builder().skuId(1L).quantity(1).build());
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }
}
