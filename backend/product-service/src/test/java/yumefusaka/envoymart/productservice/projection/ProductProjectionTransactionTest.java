package yumefusaka.envoymart.productservice.projection;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import yumefusaka.envoymart.productservice.search.ProductSyncService;
import yumefusaka.envoymart.productservice.service.impl.ProductCacheService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductProjectionTransactionTest {
    private final ProductProjectionStore store = mock(ProductProjectionStore.class);
    private final ProductProjectionInputs inputs = mock(ProductProjectionInputs.class);
    private final ProductProjectionSources sources = mock(ProductProjectionSources.class);
    private final ProductCacheService cache = mock(ProductCacheService.class);
    private final ProductSyncService index = mock(ProductSyncService.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<ProductSyncService> provider = mock(ObjectProvider.class);

    private ProductProjectionTransaction transaction(ProjectionExternalCalls external) {
        when(store.lockTask(1)).thenReturn(new ProductProjectionStore.Task(1, 20, "xid"));
        when(store.lockGuard(20)).thenReturn(new ProductProjectionStore.Guard(20, 100L, 2));
        when(store.maximumTaskId(20)).thenReturn(3L);
        when(provider.getIfAvailable()).thenReturn(index);
        return new ProductProjectionTransaction(store, inputs, sources, cache, provider, external);
    }

    @Test void 未决来源不能产生任何外部发布() {
        try (var external = new ProjectionExternalCalls()) {
            var service = transaction(external);
            doThrow(new ProjectionSourceUnresolvedException("未决")).when(sources).requireStable(20);
            assertThatThrownBy(() -> service.process(1)).isInstanceOf(ProjectionSourceUnresolvedException.class);
            verifyNoInteractions(cache, index);
            verify(store, never()).complete(anyLong(), anyLong(), anyLong(), anyString());
            verify(store, never()).failed(anyLong(), anyString());
        }
    }

    @Test void 外部失败保留任务且重新执行采用最新单调版本() {
        try (var external = new ProjectionExternalCalls()) {
            var service = transaction(external);
            doThrow(new IllegalStateException("Redis不可用")).doNothing().when(cache).invalidateProjection(20L, 3);
            assertThat(service.process(1)).isEqualTo(ProductProjectionTransaction.Outcome.EXTERNAL_FAILED);
            verify(store).failed(eq(1L), anyString());
            verify(index, never()).publishPrepared(any());
            assertThat(service.process(1)).isEqualTo(ProductProjectionTransaction.Outcome.COMPLETE);
            verify(index, times(2)).prepareCurrent(20L, 103L);
            verify(store).complete(1, 20, 3, "CACHE_AND_ES");
            assertThatThrownBy(() -> ProjectionPublicationScope.require(20L)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void 数据库结算失败向外抛不能伪装外部重试() {
        try (var external = new ProjectionExternalCalls()) {
            var service = transaction(external);
            doThrow(new IllegalStateException("数据库失败")).when(store).complete(1, 20, 3, "CACHE_AND_ES");
            assertThatThrownBy(() -> service.process(1)).hasMessage("数据库失败");
            verify(store, never()).failed(anyLong(), anyString());
        }
    }

    @Test void 禁用ES仍执行严格缓存失效和持久完成() {
        try (var external = new ProjectionExternalCalls()) {
            var service = transaction(external);
            when(provider.getIfAvailable()).thenReturn(null);
            when(store.lockGuard(20)).thenReturn(new ProductProjectionStore.Guard(20, null, 4));
            assertThat(service.process(1)).isEqualTo(ProductProjectionTransaction.Outcome.COMPLETE);
            verify(cache).invalidateProjection(20L, 4);
            verify(store).complete(1, 20, 4, "CACHE_ONLY");
            verifyNoInteractions(index);
        }
    }
}
