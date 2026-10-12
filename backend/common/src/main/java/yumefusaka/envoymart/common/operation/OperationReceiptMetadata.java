package yumefusaka.envoymart.common.operation;

/** 操作回执的协议常量，不进入业务参数或模型工具 schema。 */
public final class OperationReceiptMetadata {
    public static final String OPERATION_ID_HEADER = "X-Operation-Id";
    public static final String PARAMETERS_HASH_HEADER = "X-Operation-Parameters-Hash";
    public static final String RECEIPT_VERSION_HEADER = "X-Operation-Receipt-Version";
    public static final String REPLAYED_HEADER = "X-Operation-Replayed";
    public static final int HASH_VERSION = 1;
    public static final int RECEIPT_VERSION = 1;
    private OperationReceiptMetadata() { }
}
