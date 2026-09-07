package yumefusaka.envoymart.common.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import yumefusaka.envoymart.common.result.Result;

/**
 * 业务服务的统一异常出口。
 * <p>
 * 限定在 Servlet 应用内生效：这里的兜底 {@code @ExceptionHandler(Exception.class)} 面向 MVC 语义，
 * 一旦被响应式的网关加载，会把网关上其他框架（如 Sentinel 的限流拦截）抛出的异常一并吞掉，
 * 返回 200 + 业务错误码，使限流响应体和状态码失效。
 */
@Slf4j
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<String> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("请求参数错误");
        return Result.error(message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<String> handleBusiness(IllegalArgumentException exception) {
        log.warn("Business exception: {}", exception.getMessage());
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<String> handleUnknown(Exception exception) {
        log.error("Unhandled exception", exception);
        return Result.error(exception.getMessage());
    }
}
