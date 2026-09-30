package yumefusaka.envoymart.common.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import yumefusaka.envoymart.common.result.Result;

/**
 * 业务服务的统一异常出口。
 * <p>
 * 限定在 Servlet 应用内生效：这里的兜底 {@code @ExceptionHandler(Exception.class)} 面向 MVC 语义，
 * 一旦被响应式的网关加载，会把网关上其他框架（如 Sentinel 的限流拦截）抛出的异常一并吞掉，
 * 返回 200 + 业务错误码，使限流响应体和状态码失效。
 * <p>
 * <b>对外消息与对内日志分开</b>：调用方需要知道"是不是我请求错了"，不需要知道内网拓扑。
 * 非预期异常的原始消息一律只进日志。
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
        return Result.error(400, message);
    }

    /**
     * 参数类型不匹配（{@code /products/abc} 这种把字符串塞进 Long 的请求）。
     * <p>
     * 不返回原始消息：框架的默认文案是「Failed to convert value of type
     * 'java.lang.String' to required type 'java.lang.Long'」——把内部类型和参数名
     * 直接告诉调用方，对正常用户没有帮助，对探测者则是现成的信息。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<String> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        log.warn("Parameter type mismatch: {}={}", exception.getName(), exception.getValue());
        return Result.error(400, "参数格式不正确：" + exception.getName());
    }

    /**
     * 必填的查询参数没传（{@code /knowledge/graph/interactions} 少了 {@code items}）。
     * <p>
     * 与上面两条是同一族：都是<b>请求写错了</b>，该由调用方改，重试无用。
     * 原先它落到兜底分支被报成 500——监控上看起来是服务端故障，而真正的问题
     * 只是客户端少写了一个参数。实测就撞到过：把 {@code items} 误写成 {@code names}，
     * 拿到的是「服务暂时不可用，请稍后再试」，只能去翻服务端日志才知道少传了参数，
     * 而日志里那条栈是 {@code MissingServletRequestParameterException}——信息本来就在手里。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<String> handleMissingParam(MissingServletRequestParameterException exception) {
        log.warn("Missing request parameter: {}", exception.getParameterName());
        return Result.error(400, "缺少必需的参数：" + exception.getParameterName());
    }

    /**
     * 路径不存在（落在已路由前缀内但下游没有这个接口）。
     * <p>
     * 原先它走兜底分支，被表达成 code=500——调用方无法区分「我请求的路径写错了」
     * 和「服务端故障」，前者重试一万次也没用。现在如实返回 404。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<String> handleNotFound(NoResourceFoundException exception) {
        log.warn("No handler for {}", exception.getResourcePath());
        return Result.error(404, "接口不存在");
    }

    /**
     * 路径对得上但 HTTP 方法不对（把 GET 写成 POST）。
     * <p>
     * 与 404 同族，但比 404 更常见：只要控制器里有一条 {@code GET /xx/{id}} 这样的
     * 宽路径，任何一节路径段都会与它「路径匹配、方法不符」，所以调用方敲错路径时
     * 拿到的往往是这个异常而不是 404。原先它落兜底被报成 500，
     * <b>表现和真正的服务端故障一模一样</b>，还会在日志里刷一条 ERROR 栈把真故障淹没。
     * 实测于删掉用户侧退款入口后：{@code POST /payments/refunds} 落进了
     * {@code GET /payments/{orderId}}，回的是「服务暂时不可用」。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<String> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        log.warn("Method not allowed: {}（该路径支持 {}）",
                exception.getMethod(), exception.getSupportedHttpMethods());
        return Result.error(405, "请求方法不支持：" + exception.getMethod());
    }

    /**
     * 请求本身不合法（用户名已占用、地址不存在、金额为负）。
     * <p>
     * 返回 400 而不是 500：这是<b>客户端问题，重试无用</b>，
     * 而 500 在监控里是服务端故障的信号——把它用在这里会让告警失去意义。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<String> handleBusiness(IllegalArgumentException exception) {
        log.warn("Business exception: {}", exception.getMessage());
        return Result.error(400, exception.getMessage());
    }

    /**
     * 「状态不对」类业务失败 —— 订单已取消、支付已终态、商品正在被别人下单。
     * <p>
     * 与 {@link IllegalArgumentException} 分开列出来，是因为兜底分支对非预期异常
     * 一律回通用文案（避免泄漏内网信息）。业务代码用 {@code IllegalStateException}
     * 表达"当前状态不允许这个操作"时，消息本身就是要给用户看的，
     * 落到兜底分支会被换成"服务暂时不可用"——用户以为系统坏了，其实只是刷新一下的事。
     */
    @ExceptionHandler(IllegalStateException.class)
    public Result<String> handleIllegalState(IllegalStateException exception) {
        log.warn("Illegal state: {}", exception.getMessage());
        // 409 而不是 400：请求本身没写错，只是**当前状态不允许**——
        // 订单已取消时不能再取消、支付终态后不能再改。语义上是冲突，不是参数错误。
        // 这个区分对调用方是有用的：400 该改参数，409 该刷新后重试或换条路走
        return Result.error(409, exception.getMessage());
    }

    /**
     * 兜底 —— 非预期异常。
     * <p>
     * 原先直接把 {@code exception.getMessage()} 回给调用方，实测会把内网地址和部署细节
     * 一起吐出去：ES 停机时搜索接口返回的是
     * 「Connect to http://127.0.0.1:9200 [/127.0.0.1] failed: Connection refused」，
     * 而且这个接口是<b>匿名可达</b>的。调用方既不能据此重试，也不该知道内网拓扑；
     * 真正的排障信息留在服务端日志里。
     */
    @ExceptionHandler(Exception.class)
    public Result<String> handleUnknown(Exception exception) {
        log.error("Unhandled exception", exception);
        return Result.error(500, "服务暂时不可用，请稍后再试");
    }
}
