package yumefusaka.envoymart.productservice.projection;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import yumefusaka.envoymart.productservice.search.ProductSyncService;
import yumefusaka.envoymart.productservice.service.impl.ProductCacheService;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实本地JDBC行锁串行性，独立于仍待真实MySQL/AT验收的全局锁。 */
class ProductProjectionConcurrencyTest {
    @Test void 同商品不同任务由永久guard串行且物理缺失源不使锁失效() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:projection_concurrency_" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        var store = new ProductProjectionStore(source);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> store.ensureGuard(20));
        long first = transaction.execute(status -> store.append(20, null));
        long second = transaction.execute(status -> store.append(20, null));
        var cache = mock(ProductCacheService.class);
        @SuppressWarnings("unchecked") ObjectProvider<ProductSyncService> index = mock(ObjectProvider.class);
        var firstPublishing = new CountDownLatch(1);
        var secondAtGuard = new CountDownLatch(1);
        var secondPublishing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                firstPublishing.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("测试释放超时");
            } else secondPublishing.countDown();
            return null;
        }).when(cache).invalidateProjection(20L, second);
        var observed = spy(store);
        doAnswer(call -> {
            if (Thread.currentThread().getName().contains("第二任务")) secondAtGuard.countDown();
            return call.callRealMethod();
        }).when(observed).lockGuard(20);
        try (var external = new ProjectionExternalCalls(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var service = new ProductProjectionTransaction(observed, new ProductProjectionInputs(source), mock(ProductProjectionSources.class), cache, index, external);
            Future<?> a = workers.submit(() -> transaction.execute(status -> service.process(first)));
            try {
                assertThat(firstPublishing.await(2, TimeUnit.SECONDS)).isTrue();
                Future<?> b = workers.submit(() -> {
                    Thread.currentThread().setName("第二任务");
                    return transaction.execute(status -> service.process(second));
                });
                assertThat(secondAtGuard.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(secondPublishing.await(200, TimeUnit.MILLISECONDS)).isFalse();
                release.countDown();
                a.get(4, TimeUnit.SECONDS); b.get(4, TimeUnit.SECONDS);
                assertThat(secondPublishing.getCount()).isZero();
            } finally { release.countDown(); }
        }
        assertThat(store.candidates(0, 16)).isEmpty();
        assertThat(store.knownTarget(20)).isTrue();
    }
}
