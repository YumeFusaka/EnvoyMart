package yumefusaka.envoymart.common.operation;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import yumefusaka.envoymart.common.result.Result;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OperationExceptionHandler {
    @ExceptionHandler(OperationConflictException.class)
    public ResponseEntity<Result<Object>> conflict(OperationConflictException error) {
        return ResponseEntity.status(409).body(Result.error(409, error.getMessage()));
    }
    @ExceptionHandler(OperationReceiptUnavailableException.class)
    public ResponseEntity<Result<Object>> unavailable(OperationReceiptUnavailableException error) {
        return ResponseEntity.status(503).body(Result.error(503, error.getMessage()));
    }
}
