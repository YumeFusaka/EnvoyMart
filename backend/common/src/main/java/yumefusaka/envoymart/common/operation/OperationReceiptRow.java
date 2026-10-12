package yumefusaka.envoymart.common.operation;

import java.time.LocalDateTime;

public record OperationReceiptRow(Long id, String userId, String operationId, String toolName,
        String parametersHash, int hashVersion, int receiptVersion, String responseJson,
        LocalDateTime createdAt, LocalDateTime completedAt, String globalXid, String decisionMark) {
    public OperationReceiptRow(Long id, String userId, String operationId, String toolName,
            String parametersHash, int hashVersion, int receiptVersion, String responseJson,
            LocalDateTime createdAt, LocalDateTime completedAt) {
        this(id, userId, operationId, toolName, parametersHash, hashVersion, receiptVersion,
                responseJson, createdAt, completedAt, null, null);
    }
}
