package yumefusaka.envoymart.common.operation;

import java.util.Map;

public record OperationContext(String userId, String operationId, String toolName, String parametersHash) {
    public static OperationContext of(String userId, String operationId, String toolName, Map<String, Object> parameters) {
        return expected(userId, operationId, toolName, OperationParameters.hash(userId, toolName, parameters));
    }

    public static OperationContext expected(String userId, String operationId, String toolName, String parametersHash) {
        if (userId == null || userId.isBlank() || userId.length() > 32) throw new IllegalArgumentException("操作缺少有效用户身份");
        if (operationId == null || !operationId.matches("[\\x21-\\x7E]{1,256}")) throw new IllegalArgumentException("操作编号不合法");
        if (toolName == null || !toolName.matches("[a-z_]{1,64}")) throw new IllegalArgumentException("操作工具名不合法");
        if (parametersHash == null || !parametersHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("操作参数指纹不合法");
        return new OperationContext(userId, operationId, toolName, parametersHash);
    }
}
