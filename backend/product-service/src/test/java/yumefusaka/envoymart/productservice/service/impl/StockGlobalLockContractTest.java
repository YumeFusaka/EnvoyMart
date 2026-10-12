package yumefusaka.envoymart.productservice.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockGlobalLockContractTest {
    @org.junit.jupiter.api.BeforeAll
    static void 初始化Seata实际配置提供器所需Spring环境() {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("seata.config.type", "file")
                .withProperty("seata.config.file.name", "file.conf")
                .withProperty("seata.registry.type", "file");
        org.apache.seata.common.holder.ObjectHolder.INSTANCE.setObject("springConfigurableEnvironment", environment);
    }

    @Test
    void 普通库存和管理写必须被真实Seata扫描器收录() throws Exception {
        for (var type : java.util.List.of(StockServiceImpl.class, ProductAdminServiceImpl.class)) {
            var parser = new io.seata.integration.tx.api.interceptor.parser.GlobalTransactionalInterceptorParser();
            var handler = parser.parserInterfaceToProxy(org.mockito.Mockito.mock(type), type.getSimpleName());
            assertThat(handler).as("Seata必须实际增强%s", type.getSimpleName()).isNotNull();
            for (var method : type.getMethods()) {
                if (method.getAnnotation(org.springframework.transaction.annotation.Transactional.class) != null) {
                    assertThat(method.getAnnotation(io.seata.spring.annotation.GlobalLock.class)).as("每个签名显式声明锁，不能借同名重载的名单").isNotNull();
                    assertThat(handler.getMethodsToProxy()).as("真实Seata方法名单必须包含%s", method.getName()).contains(method.getName());
                }
            }
        }
    }
}
