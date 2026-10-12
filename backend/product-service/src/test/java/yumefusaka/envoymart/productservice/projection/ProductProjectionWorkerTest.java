package yumefusaka.envoymart.productservice.projection;

import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductProjectionWorkerTest {
    @Test void 未决任务不挡后续候选且所有消费隔离调用者XID() {
        var store = mock(ProductProjectionStore.class);
        var metadata = mock(ProductProjectionMetadata.class);
        var transactions = mock(ProductProjectionTransaction.class);
        var first = new ProductProjectionStore.Task(1, 20, "pending-source");
        var later = new ProductProjectionStore.Task(2, 21, null);
        when(store.candidates(0, 16)).thenReturn(List.of(first, later));
        when(store.candidates(2, 16)).thenReturn(List.of());
        doThrow(new ProjectionSourceUnresolvedException("未决")).when(transactions).process(1);
        when(transactions.process(2)).thenAnswer(call -> {
            assertThat(RootContext.getXID()).isNull();
            return ProductProjectionTransaction.Outcome.COMPLETE;
        });
        var readiness = new ProductProjectionReadiness();
        var worker = new ProductProjectionWorker(store, metadata, transactions, readiness);
        readiness.ready();
        RootContext.bind("caller-xid");
        try {
            worker.poll();
            assertThat(RootContext.getXID()).isEqualTo("caller-xid");
            verify(transactions).process(2);
            worker.poll();
            worker.poll();
            verify(store, times(2)).candidates(0, 16);
        } finally { RootContext.unbind(); }
    }

    @Test void 元数据读取外部失败保留任务并允许下次恢复() {
        var store = mock(ProductProjectionStore.class);
        var metadata = mock(ProductProjectionMetadata.class);
        var transactions = mock(ProductProjectionTransaction.class);
        var task = new ProductProjectionStore.Task(1, 20, null);
        doThrow(new IllegalStateException("ES超时")).doNothing().when(metadata).prepare(20);
        when(transactions.process(1)).thenReturn(ProductProjectionTransaction.Outcome.COMPLETE);
        var readiness = new ProductProjectionReadiness();
        var worker = new ProductProjectionWorker(store, metadata, transactions, readiness);
        assertThat(worker.consume(task)).isFalse();
        verifyNoInteractions(metadata, transactions, store);
        readiness.ready();
        assertThat(worker.consume(task)).isFalse();
        verifyNoInteractions(transactions);
        assertThat(worker.consume(task)).isTrue();
        verify(transactions).process(1);
        verifyNoInteractions(store);
    }
}
