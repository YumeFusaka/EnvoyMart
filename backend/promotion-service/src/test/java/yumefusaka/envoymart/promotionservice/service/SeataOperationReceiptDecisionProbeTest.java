package yumefusaka.envoymart.promotionservice.service;

import org.apache.seata.core.model.GlobalStatus;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.common.operation.OperationReceiptDecisionProbe;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SeataOperationReceiptDecisionProbeTest {
    @Test
    void 回滚失败有完整成功行也不能借终态名称放行() {
        try (var probe = new SeataOperationReceiptDecisionProbe(xid -> GlobalStatus.TimeoutRollbackFailed, xid -> 0)) {
            assertThat(probe.inspect("tc:8091:6959").status()).isEqualTo(OperationReceiptDecisionProbe.Status.UNKNOWN);
        }
    }

    @Test
    void 提交与undo都结算才接受短清理窗口后的终态() {
        var calls = new AtomicInteger();
        try (var probe = new SeataOperationReceiptDecisionProbe(xid -> GlobalStatus.Committed,
                xid -> calls.getAndIncrement() == 0 ? 1 : 0)) {
            var proof = probe.inspect("tc:8091:6959");
            assertThat(proof.status()).isEqualTo(OperationReceiptDecisionProbe.Status.COMMITTED);
            assertThat(proof.pendingUndo()).isZero();
            assertThat(calls.get()).isGreaterThanOrEqualTo(2);
        }
    }
}
