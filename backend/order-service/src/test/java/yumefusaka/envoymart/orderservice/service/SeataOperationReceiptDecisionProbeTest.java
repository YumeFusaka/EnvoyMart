package yumefusaka.envoymart.orderservice.service;

import org.apache.seata.core.model.GlobalStatus;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.common.operation.OperationReceiptDecisionProbe;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SeataOperationReceiptDecisionProbeTest {
    @Test
    void 失败未决回滚与不可达都不能证明提交() {
        for (var status : List.of(GlobalStatus.Begin, GlobalStatus.RollbackFailed, GlobalStatus.TimeoutRollbackFailed,
                GlobalStatus.Rollbacked, GlobalStatus.TimeoutRollbacked, GlobalStatus.UnKnown)) {
            try (var probe = new SeataOperationReceiptDecisionProbe(xid -> status, xid -> 0)) {
                assertThat(probe.committed("tc:8091:6959")).as(status.name()).isFalse();
            }
        }
        try (var probe = new SeataOperationReceiptDecisionProbe(xid -> { throw new IllegalStateException("TC不可达"); }, xid -> 0)) {
            assertThat(probe.committed("tc:8091:6959")).isFalse();
        }
    }

    @Test
    void 短异步提交窗口只读等到终态与undo均结算才返回成功() {
        var calls = new AtomicInteger();
        try (var probe = new SeataOperationReceiptDecisionProbe(
                xid -> calls.getAndIncrement() == 0 ? GlobalStatus.AsyncCommitting : GlobalStatus.Finished,
                xid -> calls.get() < 2 ? 1 : 0)) {
            assertThat(probe.committed("tc:8091:6959")).isTrue();
            assertThat(calls.get()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void TC调用卡住也有查询预算且关闭后的查询仍然失败封闭() {
        try (var probe = new SeataOperationReceiptDecisionProbe(xid -> {
            Thread.sleep(10000);
            return GlobalStatus.Committed;
        }, xid -> 0)) {
            long start = System.nanoTime();
            assertThat(probe.inspect("tc:8091:6959").status()).isEqualTo(OperationReceiptDecisionProbe.Status.UNKNOWN);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(4000);
            probe.close();
            assertThat(probe.inspect("tc:8091:6959").status()).isEqualTo(OperationReceiptDecisionProbe.Status.UNKNOWN);
        }
    }
}
