package yumefusaka.envoymart.aiservice.tool;

import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.common.operation.OperationContext;
import yumefusaka.envoymart.common.operation.OperationReceiptMetadata;
import yumefusaka.envoymart.common.result.Result;

import java.util.Map;
import java.util.function.Supplier;

/** 写回执只认下游同事务签发的协议元数据，超时后仅查询原操作，不换操作号重发。 */
public final class WriteReceipts {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private WriteReceipts() { }

    public record Receipt<T>(T data, boolean replayed) { }

    public static void require(boolean condition) {
        if (!condition) throw new ReceiptException(ToolResult.OperationState.UNKNOWN, "首次操作回执缺少必要业务字段，执行结果待确认");
    }

    public static OperationContext context(ToolCall call, Map<String, Object> parameters) {
        return OperationContext.of(call.requireUserId(), call.getOperationId(), call.getToolName(), parameters);
    }

    public static <T> Receipt<T> run(OperationContext operation, boolean queryOnly, Class<?> type,
                                    Supplier<ResponseEntity<Result<Object>>> query,
                                    Supplier<ResponseEntity<Result<T>>> mutate) {
        Receipt<T> previous = lookup(operation, type, query);
        if (previous != null) return previous;
        if (queryOnly) throw new ReceiptException(ToolResult.OperationState.NOT_FOUND, "尚未查到原操作的成功回执，不能推断已执行或未执行");
        try {
            ResponseEntity<Result<T>> response = mutate.get();
            validate(operation, response);
            if (type != Void.class && response.getBody().getData() == null) throw new ReceiptException(ToolResult.OperationState.UNKNOWN, "成功回执缺少业务结果，不能声明成功");
            return new Receipt<>(response.getBody().getData(), Boolean.parseBoolean(response.getHeaders().getFirst(OperationReceiptMetadata.REPLAYED_HEADER)));
        } catch (Exception failure) {
            if (failure instanceof ReceiptException receipt && receipt.state == ToolResult.OperationState.REJECTED) throw receipt;
            int code = failure instanceof feign.FeignException feign ? feign.status() : 0;
            if (code >= 400 && code < 500) throw new ReceiptException(ToolResult.OperationState.REJECTED, "原操作被业务服务拒绝，请核对对象与参数");
            Receipt<T> recovered = lookup(operation, type, query);
            if (recovered != null) return recovered;
            throw new ReceiptException(ToolResult.OperationState.UNKNOWN, "原操作没有取得成功回执，执行结果待确认，请查询原操作结果，不要重新创建操作");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Receipt<T> lookup(OperationContext operation, Class<?> type, Supplier<ResponseEntity<Result<Object>>> query) {
        try {
            ResponseEntity<Result<Object>> response = query.get();
            if (response != null && response.getBody() != null && Integer.valueOf(404).equals(response.getBody().getCode())) {
                if (matchesMetadata(operation, response) && response.getStatusCode().is2xxSuccessful()
                        && "false".equals(response.getHeaders().getFirst(OperationReceiptMetadata.REPLAYED_HEADER))) return null;
                throw new ReceiptException(ToolResult.OperationState.UNKNOWN, "未找到可核验的回执协议，禁止向未知版本提交写操作");
            }
            validate(operation, response);
            if (type != Void.class && response.getBody().getData() == null) throw new ReceiptException(ToolResult.OperationState.UNKNOWN, "持久回执缺少业务结果，不能声明成功");
            T data = type == Void.class ? null : (T) MAPPER.convertValue(response.getBody().getData(), type);
            return new Receipt<>(data, true);
        } catch (feign.FeignException error) {
            if (error.status() == 404) throw new ReceiptException(ToolResult.OperationState.UNKNOWN, "回执接口不可用，禁止向未知版本提交写操作");
            if (error.status() == 409) throw new ReceiptException(ToolResult.OperationState.REJECTED, "操作编号与原工具或参数不一致，拒绝恢复");
            throw new ReceiptException(ToolResult.OperationState.UNKNOWN, "原操作回执暂时无法查询，请稍后核对");
        } catch (ReceiptException error) { throw error; }
        catch (Exception error) { throw new ReceiptException(ToolResult.OperationState.UNKNOWN, "原操作回执暂时无法查询，请稍后核对"); }
    }

    private static void validate(OperationContext expected, ResponseEntity<? extends Result<?>> response) {
        if (response == null || response.getBody() == null) throw new ReceiptException(ToolResult.OperationState.UNKNOWN, "业务服务未返回可核验的操作回执");
        int code = response.getBody().getCode();
        if (code >= 400 && code < 500) throw new ReceiptException(ToolResult.OperationState.REJECTED, response.getBody().getMsg());
        if (!response.getStatusCode().is2xxSuccessful() || code != 200 || !matchesMetadata(expected, response)) {
            throw new ReceiptException(ToolResult.OperationState.UNKNOWN, "操作回执元数据缺失或不匹配，不能声明成功");
        }
    }

    private static boolean matchesMetadata(OperationContext expected, ResponseEntity<?> response) {
        return expected.operationId().equals(response.getHeaders().getFirst(OperationReceiptMetadata.OPERATION_ID_HEADER))
                && expected.parametersHash().equals(response.getHeaders().getFirst(OperationReceiptMetadata.PARAMETERS_HASH_HEADER))
                && String.valueOf(OperationReceiptMetadata.RECEIPT_VERSION).equals(response.getHeaders().getFirst(OperationReceiptMetadata.RECEIPT_VERSION_HEADER))
                && java.util.Set.of("true", "false").contains(response.getHeaders().getFirst(OperationReceiptMetadata.REPLAYED_HEADER));
    }

    public static ToolResult failure(Exception error, String action) {
        if (error instanceof ReceiptException receipt) return ToolResult.builder().success(false)
                .operationState(receipt.state).transientFailure(receipt.state == ToolResult.OperationState.UNKNOWN)
                .errorMessage(receipt.getMessage()).build();
        return Downstream.failure(action, error).toBuilder().operationState(ToolResult.OperationState.REJECTED).build();
    }

    public static final class ReceiptException extends RuntimeException {
        private final ToolResult.OperationState state;
        public ReceiptException(ToolResult.OperationState state, String message) { super(message); this.state = state; }
    }
}
