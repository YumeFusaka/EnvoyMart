package yumefusaka.envoymart.orderservice.service;

import io.seata.spring.annotation.GlobalLock;
import io.seata.spring.annotation.GlobalTransactional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.orderservice.model.CheckoutRequest;
import yumefusaka.envoymart.orderservice.service.impl.CartServiceImpl;
import yumefusaka.envoymart.orderservice.service.impl.OrderDomainServiceImpl;

import static org.assertj.core.api.Assertions.assertThat;

class OrderWriteIsolationContractTest {
    @org.junit.jupiter.api.BeforeAll
    static void 初始化Seata实际配置提供器所需Spring环境() {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("seata.config.type", "file")
                .withProperty("seata.config.file.name", "file.conf")
                .withProperty("seata.registry.type", "file");
        org.apache.seata.common.holder.ObjectHolder.INSTANCE.setObject("springConfigurableEnvironment", environment);
    }

    @Test
    void 本地订单和购物车写必须被真实Seata扫描器收录() throws Exception {
        for (var type : java.util.List.of(CartServiceImpl.class, OrderDomainServiceImpl.class,
                yumefusaka.envoymart.orderservice.service.impl.OrderAdminServiceImpl.class,
                yumefusaka.envoymart.orderservice.service.impl.AfterSaleServiceImpl.class, OperationReceiptService.class)) {
            var parser = new io.seata.integration.tx.api.interceptor.parser.GlobalTransactionalInterceptorParser();
            var handler = parser.parserInterfaceToProxy(org.mockito.Mockito.mock(type), type.getSimpleName());
            assertThat(handler).as("Seata必须实际增强%s", type.getSimpleName()).isNotNull();
            for (var method : type.getMethods()) {
                if (method.getAnnotation(Transactional.class) != null) {
                    assertThat(method.getAnnotation(GlobalLock.class)).as("每个签名显式声明锁，不能借同名重载的名单").isNotNull();
                    assertThat(handler.getMethodsToProxy()).as("真实Seata方法名单必须包含%s", method.getName()).contains(method.getName());
                }
            }
        }
    }

    @Test
    void 勾选单条必须有本地事务而结算仍发起全局事务() throws Exception {
        assertThat(CartServiceImpl.class.getMethod("setSelected", String.class, Long.class, boolean.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(OrderDomainServiceImpl.class.getMethod("checkout", String.class, CheckoutRequest.class)
                .getAnnotation(GlobalTransactional.class)).isNotNull();
    }

    @Test
    void 批量任务不能把全部订单放进一个本地事务() throws Exception {
        assertThat(OrderDomainServiceImpl.class.getMethod("closeExpiredOrders", int.class)
                .getAnnotation(Transactional.class)).isNull();
        assertThat(OrderDomainServiceImpl.class.getMethod("completeExpiredReceipts", int.class)
                .getAnnotation(Transactional.class)).isNull();
    }
}
