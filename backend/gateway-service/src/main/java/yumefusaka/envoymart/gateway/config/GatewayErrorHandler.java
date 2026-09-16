package yumefusaka.envoymart.gateway.config;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关所有「拒绝」的唯一出口 —— 鉴权失败与限流拦截都从这里写出响应。
 * <p>
 * <b>为什么必须是一个统一出口</b>：网关里原本有两处各自写响应的地方——{@code JwtGatewayFilter}
 * 直接 {@code setStatusCode().setComplete()}，Sentinel 走它自带的
 * {@code SentinelGatewayBlockExceptionHandler}。两者看起来互不相干，实际上会打架：
 * <p>
 * Gateway 的过滤器链是<b>同步递归调用</b>的，{@code chain.filter()} 会立即执行后续过滤器的
 * 方法体。Sentinel 的过滤器顺序在最前，它的实现也是「先调 {@code chain.filter(exchange)}
 * 再做限流判定」——于是鉴权分支会在限流判定<em>之前</em>就把响应提交掉，限流随后想写的 429
 * 再也写不进去。
 * <p>
 * 实测（100 并发打一个 20rps 的路由）：没超限的 20 个正常 401；超限的 80 个在服务端刷出
 * {@code 500 Server Error}，而客户端拿到的是 <b>HTTP 200 + 空 body</b>——「被限流」被读成了
 * 「成功但没有数据」。对照带 token 的同类请求（鉴权分支不提前提交响应）则能正常收到 429。
 * 同一个限流规则，两种客户端看到两种结果。
 * <p>
 * 改成「拒绝一律抛异常」之后，谁都不会提前碰响应，由本类一次性写出。
 * <p>
 * <b>顺序必须是最高优先级。</b>Spring 的 {@code ExceptionHandlingWebHandler} 按 order 依次
 * 征询各 {@code WebExceptionHandler}，只有前一个返回 {@code Mono.error} 时后一个才轮得到；
 * 排在自带处理器之后等于永远轮不到自己。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayErrorHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        Resolved resolved = resolve(ex);
        if (resolved == null) {
            // 不是本类负责的异常，原样交给后面的处理器（含 Spring 默认的错误处理）
            return Mono.error(ex);
        }

        ServerHttpResponse response = exchange.getResponse();
        String path = exchange.getRequest().getPath().value();

        if (response.isCommitted()) {
            // 不该再走到这里——拒绝路径已经全部改成抛异常了。真出现说明又有人在异常抛出前
            // 提交了响应，此时改写只会抛 IllegalStateException。留痕，让「限流没返回 429」
            // 在日志里有据可查，而不是静默地表现成一个 200。
            log.error("[Gateway] 拒绝响应无法写入：已被提前提交 path={} want={} ex={}",
                    path, resolved.status.value(), ex.getClass().getName());
            return Mono.error(ex);
        }

        log.warn("[Gateway] 拒绝请求 path={} status={} msg={} ex={}",
                path, resolved.status.value(), resolved.message, ex.getClass().getSimpleName());

        byte[] bytes = resolved.toJson().getBytes(StandardCharsets.UTF_8);
        response.setStatusCode(resolved.status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setContentLength(bytes.length);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /** 返回 null 表示「不是本类负责的异常」，交给后续处理器。 */
    private Resolved resolve(Throwable ex) {
        if (ex instanceof BlockException) {
            return new Resolved(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }
        if (ex instanceof ResponseStatusException statusException) {
            HttpStatus status = HttpStatus.resolve(statusException.getStatusCode().value());
            if (status == null) {
                return null;
            }
            String reason = statusException.getReason() == null
                    ? status.getReasonPhrase()
                    : statusException.getReason();
            return new Resolved(status, reason);
        }
        return null;
    }

    private record Resolved(HttpStatus status, String message) {

        /** 沿用项目约定：HTTP 状态码照实表达，body 里带 code 与可读提示。 */
        String toJson() {
            return "{\"code\": " + status.value() + ", \"msg\": \"" + escape(message) + "\"}";
        }

        private static String escape(String raw) {
            return raw.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
