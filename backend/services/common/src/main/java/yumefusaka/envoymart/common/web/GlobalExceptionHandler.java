package yumefusaka.envoymart.common.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import yumefusaka.envoymart.common.result.Result;

@Slf4j
@RestControllerAdvice
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
