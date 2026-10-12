package yumefusaka.envoymart.common.operation;

import org.springframework.http.ResponseEntity;
import yumefusaka.envoymart.common.result.Result;

public record OperationReceiptReply<T>(OperationContext operation, Result<T> body, boolean replayed) {
    /** 区分已实现回执协议的权威未找到与旧服务根本不存在回执路由。 */
    public static ResponseEntity<Result<Object>> notFound(OperationContext operation) {
        return new OperationReceiptReply<Object>(operation, Result.error(404, "尚未查到原操作成功回执"), false).response();
    }
    public ResponseEntity<Result<T>> response() {
        return ResponseEntity.ok()
                .header(OperationReceiptMetadata.OPERATION_ID_HEADER, operation.operationId())
                .header(OperationReceiptMetadata.PARAMETERS_HASH_HEADER, operation.parametersHash())
                .header(OperationReceiptMetadata.RECEIPT_VERSION_HEADER, "1")
                .header(OperationReceiptMetadata.REPLAYED_HEADER, String.valueOf(replayed)).body(body);
    }
}
