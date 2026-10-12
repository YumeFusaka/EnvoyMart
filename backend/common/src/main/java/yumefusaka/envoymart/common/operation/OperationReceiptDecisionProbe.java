package yumefusaka.envoymart.common.operation;

/** 全局决议的只读端口；事务框架与undo存储由各服务适配。 */
@FunctionalInterface
public interface OperationReceiptDecisionProbe {
    enum Status { COMMITTED, FINISHED, ROLLED_BACK, UNKNOWN }

    record Proof(Status status, long pendingUndo, String detail) { }

    Proof inspect(String globalXid);

    static OperationReceiptDecisionProbe unavailable() {
        return xid -> new Proof(Status.UNKNOWN, -1, "全局事务决议检查未装配");
    }
}
