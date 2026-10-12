package yumefusaka.envoymart.common.operation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OperationReceiptDecisionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OperationContext operation = OperationContext.of("u", "original-op", "cart_checkout", Map.of("receiverName", "用户"));

    private OperationReceiptStore store(String xid, String mark, OperationReceiptDecisionProbe probe) {
        var repository = mock(OperationReceiptStore.Repository.class);
        var time = LocalDateTime.of(2026, 10, 11, 18, 0);
        when(repository.find(operation.userId(), operation.operationId())).thenReturn(new OperationReceiptRow(39L,
                operation.userId(), operation.operationId(), operation.toolName(), operation.parametersHash(), 1, 1,
                "{\"code\":200,\"msg\":\"成功\",\"data\":{\"id\":751}}", time, time, xid, mark));
        return new OperationReceiptStore(repository, mapper, probe);
    }

    @Test
    void 完整成功JSON但全局未决失败或未知均不能声明提交() {
        for (String state : java.util.List.of("Begin", "Committing", "AsyncCommitting", "Rollbacking", "TimeoutRollbackFailed", "UnKnown")) {
            var store = store("tc:8091:6959", "GLOBAL_PENDING",
                    xid -> new OperationReceiptDecisionProbe.Proof(OperationReceiptDecisionProbe.Status.UNKNOWN, 0, state));
            assertThatThrownBy(() -> store.replay(operation, Map.class)).as(state).isInstanceOf(OperationReceiptUnavailableException.class);
        }
    }

    @Test
    void 终态成功且undo结算后才能重放首次JSON() {
        for (var status : java.util.List.of(OperationReceiptDecisionProbe.Status.COMMITTED, OperationReceiptDecisionProbe.Status.FINISHED)) {
            assertThatThrownBy(() -> store("tc:8091:6959", "GLOBAL_PENDING",
                    xid -> new OperationReceiptDecisionProbe.Proof(status, 1, "undo待结算")).replay(operation, Map.class))
                    .isInstanceOf(OperationReceiptUnavailableException.class);
            assertThat(store("tc:8091:6959", "GLOBAL_PENDING",
                    xid -> new OperationReceiptDecisionProbe.Proof(status, 0, "undo已结算")).replay(operation, Map.class)
                    .orElseThrow().body().getData()).containsEntry("id", 751);
        }
    }

    @Test
    void 明确回滚但成功行仍在属于完整性异常不能重放() {
        assertThatThrownBy(() -> store("tc:8091:6959", "GLOBAL_PENDING", xid ->
                new OperationReceiptDecisionProbe.Proof(OperationReceiptDecisionProbe.Status.ROLLED_BACK, 0, "Rollbacked"))
                .replay(operation, Map.class)).isInstanceOf(OperationReceiptUnavailableException.class);
    }

    @Test
    void TC不可达与历史结算缺XID都不能借JSON成功放行() {
        assertThatThrownBy(() -> store("tc:8091:6959", "GLOBAL_COMMITTED", xid -> { throw new IllegalStateException("TC不可达"); })
                .replay(operation, Map.class)).isInstanceOf(OperationReceiptUnavailableException.class);
        assertThatThrownBy(() -> store(null, null, OperationReceiptDecisionProbe.unavailable()).replay(operation, Map.class))
                .isInstanceOf(OperationReceiptUnavailableException.class);
        assertThat(store(null, "LOCAL", OperationReceiptDecisionProbe.unavailable()).replay(operation, Map.class)).isPresent();
    }
}
