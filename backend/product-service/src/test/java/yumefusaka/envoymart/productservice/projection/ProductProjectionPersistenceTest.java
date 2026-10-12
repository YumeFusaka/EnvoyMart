package yumefusaka.envoymart.productservice.projection;

import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只证明本地事务/重启持久性；真正AT回滚由新runId端到端取证。 */
class ProductProjectionPersistenceTest {
    private ProductProjectionStore store;
    private TransactionTemplate transactions;
    private JdbcTemplate jdbc;
    private ProductProjectionRequests requests;
    private ProductProjectionWorker worker;
    @BeforeEach void init() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:projection_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        store = new ProductProjectionStore(source);
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        worker = mock(ProductProjectionWorker.class);
        @SuppressWarnings("unchecked") ObjectProvider<ProductProjectionWorker> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(worker);
        requests = new ProductProjectionRequests(store, provider);
    }

    @Test void 本地回滚同时撤销任务且不唤醒消费者() {
        assertThatThrownBy(() -> transactions.execute(status -> {
            requests.request(List.of(20L));
            throw new IllegalStateException("业务失败");
        })).hasMessage("业务失败");
        assertThat(store.candidates(0, 16)).isEmpty();
        verifyNoInteractions(worker);
    }

    @Test void 本地提交立即唤醒持久任务并保持同一消费路径() {
        transactions.executeWithoutResult(status -> requests.request(List.of(20L, 20L, -1L)));
        var candidates = store.candidates(0, 16);
        assertThat(candidates).hasSize(1);
        verify(worker).consume(candidates.get(0));
        assertThat(candidates.get(0).globalXid()).isNull();
    }

    @Test void AT来源只登记不以本地提交触发发布且任务可重启扫描() {
        RootContext.bind("test-global-xid");
        try { transactions.executeWithoutResult(status -> requests.request(List.of(20L))); }
        finally { RootContext.unbind(); }
        var candidates = store.candidates(0, 16);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).globalXid()).isEqualTo("test-global-xid");
        verifyNoInteractions(worker);
        var restarted = new ProductProjectionStore(jdbc.getDataSource());
        assertThat(restarted.candidates(0, 16)).containsExactlyElementsOf(candidates);
    }

    @Test void 删除业务行不移除永久版本闸且可重新扫描墓碑() {
        transactions.executeWithoutResult(status -> {
            store.ensureGuard(20);
            store.initializeVersionBase(20, 100);
        });
        assertThat(store.knownTarget(20)).isTrue();
        assertThat(store.startupTargets()).containsExactly(20L);
        assertThat(store.knownTarget(999)).isFalse();
    }

    @Test void 启动重建不因外部故障串行阻塞服务就绪() {
        transactions.executeWithoutResult(status -> requests.rebuild(List.of(20L, 21L)));
        assertThat(store.candidates(0, 16)).hasSize(2);
        verifyNoInteractions(worker);
    }
}
