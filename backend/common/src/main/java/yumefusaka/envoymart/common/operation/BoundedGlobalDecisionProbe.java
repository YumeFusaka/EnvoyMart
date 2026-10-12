package yumefusaka.envoymart.common.operation;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;

/** 全局决议检查的有界调度；框架状态读取与各库undo仍由适配器提供。 */
@Slf4j
public class BoundedGlobalDecisionProbe implements OperationReceiptDecisionProbe, AutoCloseable {
    public record Observation(Status status, boolean settling, String detail) {
        public static Observation fromStatusName(String name) {
            if (name == null) return new Observation(Status.UNKNOWN, false, "TC未知状态");
            return switch (name) {
                case "Committed" -> new Observation(Status.COMMITTED, false, name);
                case "Finished" -> new Observation(Status.FINISHED, false, name);
                case "Rollbacked", "TimeoutRollbacked" -> new Observation(Status.ROLLED_BACK, false, name);
                case "Committing", "CommitRetrying", "AsyncCommitting" -> new Observation(Status.UNKNOWN, true, name);
                default -> new Observation(Status.UNKNOWN, false, name);
            };
        }
    }

    @FunctionalInterface
    public interface StatusReader { Observation read(String xid) throws Exception; }

    private final StatusReader statuses;
    private final ToLongFunction<String> undo;
    private final java.util.concurrent.ExecutorService queries = Executors.newVirtualThreadPerTaskExecutor();

    public BoundedGlobalDecisionProbe(StatusReader statuses, ToLongFunction<String> undo) {
        this.statuses = statuses;
        this.undo = undo;
    }

    @Override
    public Proof inspect(String xid) {
        if (xid == null || !xid.matches("[\\x21-\\x7E]{1,128}")) return new Proof(Status.UNKNOWN, -1, "XID缺失或不合法");
        java.util.concurrent.Future<Proof> task = null;
        try {
            task = queries.submit(() -> inspectCurrent(xid));
            return task.get(2500, TimeUnit.MILLISECONDS);
        } catch (Exception unavailable) {
            if (task != null) task.cancel(true);
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[事务决议] 阶段=权威检查超时或不可用 xid={} errorType={}", xid, unavailable.getClass().getSimpleName());
            return new Proof(Status.UNKNOWN, -1, "TC或undo查询超时或不可用");
        }
    }

    private Proof inspectCurrent(String xid) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
        try {
            while (true) {
                Observation observed = statuses.read(xid);
                long pending = undo.applyAsLong(xid);
                boolean successful = observed.status() == Status.COMMITTED || observed.status() == Status.FINISHED;
                if (successful && pending == 0) {
                    log.info("[事务决议] 阶段=成功已核验 xid={} tcStatus={} undo=0", xid, observed.detail());
                    return new Proof(observed.status(), pending, observed.detail());
                }
                if (!(observed.settling() || successful && pending > 0) || System.nanoTime() >= deadline) {
                    log.warn("[事务决议] 阶段=未证明提交 xid={} tcStatus={} undo={}", xid, observed.detail(), pending);
                    return new Proof(observed.status(), pending, observed.detail());
                }
                Thread.sleep(100);
            }
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[事务决议] 阶段=权威检查不可用 xid={} errorType={}", xid, unavailable.getClass().getSimpleName());
            return new Proof(Status.UNKNOWN, -1, "TC或undo查询不可用");
        }
    }

    public boolean committed(String xid) {
        Proof proof = inspect(xid);
        return (proof.status() == Status.COMMITTED || proof.status() == Status.FINISHED) && proof.pendingUndo() == 0;
    }

    @jakarta.annotation.PreDestroy
    @Override public void close() { queries.shutdownNow(); }
}
