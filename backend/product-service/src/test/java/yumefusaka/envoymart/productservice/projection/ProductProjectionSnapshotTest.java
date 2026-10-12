package yumefusaka.envoymart.productservice.projection;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.productservice.search.*;
import yumefusaka.envoymart.productservice.mapper.*;
import yumefusaka.envoymart.productservice.service.CategoryService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductProjectionSnapshotTest {
    private ProductProjectionReadiness ready() {
        var readiness = new ProductProjectionReadiness(); readiness.ready(); return readiness;
    }
    @Test void 任意未知ID不制造永久屏障且未决源不执行回填() {
        var store = mock(ProductProjectionStore.class);
        var inputs = mock(ProductProjectionInputs.class);
        var sources = mock(ProductProjectionSources.class);
        var service = new ProductProjectionSnapshotTransaction(store, inputs, sources, ready());
        long unknownVersion = service.read(999, version -> version);
        assertThat(unknownVersion).isZero();
        verify(store, never()).ensureGuard(anyLong());
        var publish = new java.util.concurrent.atomic.AtomicBoolean();
        doThrow(new ProjectionSourceUnresolvedException("未决")).when(sources).requireStable(20);
        assertThatThrownBy(() -> service.read(20, version -> { publish.set(true); return true; })).isInstanceOf(ProjectionSourceUnresolvedException.class);
        assertThat(publish).isFalse();
    }

    @Test void 已知目标按最后成功版本与当前任务最大值回填() {
        var store = mock(ProductProjectionStore.class);
        when(store.knownTarget(20)).thenReturn(true);
        when(store.lockGuard(20)).thenReturn(new ProductProjectionStore.Guard(20, 100L, 9));
        when(store.maximumTaskId(20)).thenReturn(7L);
        var service = new ProductProjectionSnapshotTransaction(store, mock(ProductProjectionInputs.class), mock(ProductProjectionSources.class), ready());
        long snapshotVersion = service.read(20, version -> version);
        assertThat(snapshotVersion).isEqualTo(9L);
        verify(store).ensureGuard(20);
    }

    @Test void 物理删除发布永久不可检索墓碑并携带外部版本() {
        var repository = mock(ProductSearchRepository.class);
        var sync = new ProductSyncService(mock(ProductSpuMapper.class), mock(ProductSkuMapper.class),
                mock(SpuAttributeValueMapper.class), mock(ProductAttributeMapper.class), mock(CategoryService.class),
                repository, mock(ProductProjectionRequests.class), mock(ProductProjectionStore.class));
        assertThatThrownBy(() -> sync.prepareCurrent(20L, 103)).isInstanceOf(IllegalStateException.class);
        try (var scope = new ProjectionPublicationScope(20)) {
            sync.publishPrepared(sync.prepareCurrent(20L, 103));
        }
        var projection = org.mockito.ArgumentCaptor.forClass(ProductIndex.class);
        verify(repository).save(projection.capture());
        assertThat(projection.getValue().getStatus()).isEqualTo(-1);
        assertThat(projection.getValue().getTotalStock()).isZero();
        assertThat(projection.getValue().getProjectionVersion()).isEqualTo(103L);
        verify(repository, never()).deleteById(anyLong());
        assertThat(ProductIndex.class.getAnnotation(org.springframework.data.elasticsearch.annotations.Document.class).versionType())
                .isEqualTo(org.springframework.data.elasticsearch.annotations.Document.VersionType.EXTERNAL_GTE);
    }

    @Test void 消费者独立事务声明必须被真实Seata扫描器收录() throws Exception {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("seata.config.type", "file").withProperty("seata.config.file.name", "file.conf").withProperty("seata.registry.type", "file");
        org.apache.seata.common.holder.ObjectHolder.INSTANCE.setObject("springConfigurableEnvironment", environment);
        for (var type : java.util.List.of(ProductProjectionTransaction.class, ProductProjectionSnapshotTransaction.class, ProductProjectionMetadata.class)) {
            var parser = new io.seata.integration.tx.api.interceptor.parser.GlobalTransactionalInterceptorParser();
            var handler = parser.parserInterfaceToProxy(mock(type), type.getSimpleName());
            assertThat(handler).isNotNull();
            for (var method : type.getMethods()) {
                var annotation = method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
                if (annotation == null) continue;
                assertThat(annotation.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
                assertThat(method.getAnnotation(io.seata.spring.annotation.GlobalLock.class)).isNotNull();
                assertThat(handler.getMethodsToProxy()).contains(method.getName());
            }
        }
        assertThat(yumefusaka.envoymart.productservice.ProductServiceApplication.class.getAnnotation(org.springframework.scheduling.annotation.EnableScheduling.class)).isNotNull();
    }

    @Test void 启动结构检查未完成时miss不读库不回源不回填() {
        var store = mock(ProductProjectionStore.class);
        var inputs = mock(ProductProjectionInputs.class);
        var sources = mock(ProductProjectionSources.class);
        var readiness = new ProductProjectionReadiness();
        var snapshots = new ProductProjectionSnapshotTransaction(store, inputs, sources, readiness);
        var published = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(() -> snapshots.read(20, version -> { published.set(true); return true; }))
                .isInstanceOf(ProjectionSourceUnresolvedException.class);
        assertThat(published).isFalse();
        verifyNoInteractions(store, inputs, sources);
    }
}
