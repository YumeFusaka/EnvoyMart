package yumefusaka.envoymart.common.operation;

public class OperationConflictException extends IllegalStateException {
    public OperationConflictException() { super("同一操作编号与已提交的工具或参数不一致"); }
}
