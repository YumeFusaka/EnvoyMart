package yumefusaka.envoymart.productservice.projection;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.common.operation.OperationReceiptDecisionProbe;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductProjectionSourcesTest {
    @Test void 检查同商品后续未决来源不只检查当前候选任务() {
        var store = mock(ProductProjectionStore.class);
        var decisions = mock(OperationReceiptDecisionProbe.class);
        when(store.pendingSources(20)).thenReturn(List.of("committed", "later-pending"));
        when(decisions.inspect("committed")).thenReturn(new OperationReceiptDecisionProbe.Proof(OperationReceiptDecisionProbe.Status.FINISHED, 0, "完成"));
        when(decisions.inspect("later-pending")).thenReturn(new OperationReceiptDecisionProbe.Proof(OperationReceiptDecisionProbe.Status.UNKNOWN, 1, "未决"));
        assertThatThrownBy(() -> new ProductProjectionSources(store, decisions).requireStable(20)).isInstanceOf(ProjectionSourceUnresolvedException.class);
        verify(decisions).inspect("later-pending");
    }

    @Test void 已提交但仍有undo不能发布() {
        var store = mock(ProductProjectionStore.class);
        when(store.pendingSources(20)).thenReturn(List.of("commit"));
        var sources = new ProductProjectionSources(store, xid -> new OperationReceiptDecisionProbe.Proof(OperationReceiptDecisionProbe.Status.COMMITTED, 1, "undo未清"));
        assertThatThrownBy(() -> sources.requireStable(20)).isInstanceOf(ProjectionSourceUnresolvedException.class);
    }
}
