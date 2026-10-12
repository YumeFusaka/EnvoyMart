package yumefusaka.envoymart.common.operation;

/** 仅表示回执认领唯一键竞争；领域业务的其他唯一键不能转换为这个异常。 */
public class OperationClaimConflictException extends RuntimeException {
    public OperationClaimConflictException(Throwable cause) { super("操作正在由另一请求处理", cause); }
}
