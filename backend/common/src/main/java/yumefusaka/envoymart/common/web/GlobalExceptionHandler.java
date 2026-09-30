package yumefusaka.envoymart.common.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import yumefusaka.envoymart.common.result.Result;

import java.sql.SQLException;

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
        return Result.error(400, firstFieldMessage(exception.getBindingResult()));
    }

    /**
     * 查询对象绑定失败（{@code ?createdFrom=昨天} 这种塞不进 {@code LocalDateTime} 的值）。
     * <p>
     * 与上面那条是同一个出口的两个入口：{@code @Valid @RequestBody} 抛
     * {@code MethodArgumentNotValidException}，而 {@code GET} 上的查询对象走的是它的父类
     * {@code BindException}。只补了前者的话，「请求体写错」是 400 + 一句人话，
     * 「查询参数写错」却落到兜底分支变成「服务暂时不可用」——两者的成因完全一样。
     */
    @ExceptionHandler(BindException.class)
    public Result<String> handleBind(BindException exception) {
        return Result.error(400, firstFieldMessage(exception.getBindingResult()));
    }

    private String firstFieldMessage(BindingResult binding) {
        return binding.getFieldErrors().stream()
                .findFirst()
                .map(GlobalExceptionHandler::describeFieldError)
                .orElse("请求参数错误");
    }

    /**
     * 把一条字段错误转成给调用方看的一句话。
     * <p>
     * 只有校验注解上写了 {@code message} 的才回原文（本项目的都写了中文）；
     * 类型转换失败没有自定义消息，框架给的是「Failed to convert property value of type
     * 'java.lang.String' to required type 'java.time.LocalDateTime' for property 'createdFrom'」——
     * 与本类 {@link #handleTypeMismatch} 要挡的是同一种东西：把内部类型和属性名
     * 直接告诉调用方，对正常用户没有帮助，对探测者则是现成的信息。
     */
    private static String describeFieldError(FieldError error) {
        if (!"typeMismatch".equals(error.getCode())) {
            String message = error.getDefaultMessage();
            return message == null || message.isBlank() ? "参数校验未通过：" + error.getField() : message;
        }
        return "参数格式不正确：" + error.getField();
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
     * 缺少必需的请求头，目前只有身份头 {@code X-User-Id}。
     * <p>
     * 与缺参数同族，是那个处理器的兄弟：{@code MissingRequestHeaderException} 不在
     * {@code MissingServletRequestParameterException} 的继承链上，只补了后者就会
     * 让「少一个参数」是 400、「少一个头」是 500 —— 而两者的成因完全一样。
     * <p>
     * 这个头由网关注入，正常流量不会缺。真缺的时候通常是<b>绕过了网关</b>
     * （服务间直连、或本地调试直接打端口），那时候回「服务暂时不可用」+ 一条 ERROR 栈，
     * 是最没用的答案：问题在调用方，日志却被服务端故障占满。
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public Result<String> handleMissingHeader(MissingRequestHeaderException exception) {
        log.warn("Missing request header: {}", exception.getHeaderName());
        return Result.error(400, "缺少必需的请求头：" + exception.getHeaderName());
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
     * 请求体读不出来 —— 不是合法 JSON，或者超出了 {@link RequestSizeLimitFilter} 之外的
     * 解析层限制。
     * <p>
     * 与上面几条同族：问题在调用方，重试无用。原先是落到兜底分支的，于是
     * 「报文超长」被报成「服务暂时不可用，请稍后再试」——调用方会一直重试同一个必然失败的请求，
     * 而监控上看起来是服务端在故障。
     * <p>
     * <b>不回原始消息</b>：Jackson 的解析异常里带着出错位置与上下文片段，
     * 而这段报文可能正是别人的敏感数据；这里只需要告诉调用方「这报文我读不了」。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<String> handleUnreadableBody(HttpMessageNotReadableException exception) {
        log.warn("Unreadable request body: {}", exception.getMessage());
        return Result.error(400, "请求体格式不正确或内容过大");
    }

    /**
     * 兜底 —— 非预期异常。
     * <p>
     * 原先直接把 {@code exception.getMessage()} 回给调用方，实测会把内网地址和部署细节
     * 一起吐出去：ES 停机时搜索接口返回的是
     * 「Connect to http://127.0.0.1:9200 [/127.0.0.1] failed: Connection refused」，
     * 而且这个接口是<b>匿名可达</b>的。调用方既不能据此重试，也不该知道内网拓扑；
     * 真正的排障信息留在服务端日志里。
     * <p>
     * <b>但「数据库说这条数据不合规」要在这一层被认出来</b>，见
     * {@link #describeConstraintViolation}。它落到这里就变成 500，而 500 在监控里
     * 是服务端故障的信号——为客户端写错的东西拉响服务端告警，是这个类从第一行起就在避免的事。
     */
    @ExceptionHandler(Exception.class)
    public Result<String> handleUnknown(Exception exception) {
        String constraint = describeConstraintViolation(exception);
        if (constraint != null) {
            log.warn("Constraint violation: {}", exception.getMessage());
            return Result.error(400, constraint);
        }
        log.error("Unhandled exception", exception);
        return Result.error(500, "服务暂时不可用，请稍后再试");
    }

    /**
     * 从异常链里认出「这一次写入是调用方的数据不合规」，返回给调用方的一句话；认不出返回 null。
     * <p>
     * 两类：<b>值太长塞不进列</b>（SQLState 22001）和<b>唯一键/外键冲突</b>（23000）。
     * 两者都<b>只可能由请求内容决定</b>——缩短字段、换个编码重试就好了——所以是 400 而不是 500。
     * 实测撞到过前者：接口契约写「详情正文最长 20 万字符」，而列是 MySQL {@code text}
     * （上限 65535 <b>字节</b>），于是校验放行、落库报错，客户端拿到的是
     * 「服务暂时不可用」，日志里只有一句 {@code Data too long for column 'detail_html'}。
     * <p>
     * <b>为什么在这里翻异常链，而不是直接 {@code @ExceptionHandler(DataIntegrityViolationException.class)}</b>：
     * 那个类来自 spring-tx，而 {@code common} 没有这个依赖（它只带 spring-web / spring-webmvc）。
     * 把依赖加成 optional 也不成立——Spring 在<i>启动时</i>就要解析 {@code @ExceptionHandler}
     * 上的类型，缺少该类的服务会直接起不来。<b>依赖可以可选，启动期的类型解析不能。</b>
     * {@link SQLException} 在 JDK 里，永远都在。
     * <p>
     * SQLState 是 MySQL 的口径，本项目的数据源就是 MySQL。换库时这一处要跟着改——
     * 值得的代价是：换库时你会看见这段注释，而不是在线上盯着一条没人看得懂的 500。
     */
    private static String describeConstraintViolation(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (!(t instanceof SQLException sql)) {
                continue;
            }
            String state = sql.getSQLState();
            if (state == null) {
                continue;
            }
            if (state.startsWith("22")) {
                return "提交的内容超出长度或取值范围限制，请检查后重试";
            }
            if (state.startsWith("23")) {
                return "该数据已存在或与已有数据冲突，请勿重复提交";
            }
        }
        return null;
    }
}
