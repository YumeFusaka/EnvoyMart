package yumefusaka.envoymart.gateway.filter;

import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import yumefusaka.envoymart.common.web.RequestId;

/**
 * 在边界上把请求标识定下来，再交给下游。
 * <p>
 * <b>为什么必须由网关发</b>：客户端可以不传（curl、第三方回调、压测脚本都不会传）。
 * 每个服务各发各的，就会变成「同一件事在不同服务里叫不同名字」，跨服务排查依旧断线。
 * 定在最外层，下游只管读。
 * <p>
 * <b>为什么替换客户端给的值而不是直接透传</b>：客户端那个值要过一遍字符集与长度校验
 * （见 {@link RequestId#resolve}），否则一个带换行的头就能往所有下游的日志里插伪造行。
 * 校验通过时保留客户端的值——前端一次操作会发多个请求，用同一个 id 串起来是它的正当用途。
 * <p>
 * <b>顺序取 -200：在鉴权（-100）之前、限流（Sentinel，最高优先级）之后。</b>
 * 排在鉴权之前是为了让"令牌无效""账号已禁用"这些拒绝路径也有标识可查——那正是
 * 最需要线索的路径；排在限流之后是因为限流一旦拒绝就不会再走后面的过滤器，
 * 那条路径上有没有标识取决于 Sentinel 自己的日志，不在本类的职责内。
 * <p>
 * <b>网关自己的 MDC 只覆盖同步的那一段</b>：这里是 Reactor 线程模型，线程在请求之间复用，
 * 而 MDC 是线程级的——放久了会串到同线程上另一个请求的日志里，得到一条"看起来正常但归错了人"
 * 的记录，比没有标识更糟。好在 Gateway 的过滤器链是同步递归调用的（见
 * {@code JwtGatewayFilter.reject} 的注释）：把 put/remove 夹在 {@code chain.filter} 两侧，
 * 覆盖到的正是各过滤器的<b>方法体</b>——拒绝请求的日志全在这里；而响应真正写出去之后
 * 才算完的那部分（错误信号、完成回调）拿不到标识，这一点不装作能做到。
 */
@Component
public class RequestIdGatewayFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = RequestId.resolve(exchange.getRequest().getHeaders().getFirst(RequestId.HEADER));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(RequestId.HEADER, requestId)
                .build();
        // 响应头也带上：用户报障时拿得到的就是它。
        // 但只 set 一次是不够的 —— 下游收到这个头后会原样回写（各服务的 RequestIdFilter），
        // 而透传下游响应头用的是 addAll 语义，两个同值会拼成
        // 「abc, abc」这种一眼看不出对错的值。提交前收敛成单值，两条来路都覆盖到。
        exchange.getResponse().getHeaders().set(RequestId.HEADER, requestId);
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(RequestId.HEADER, requestId);
            return Mono.empty();
        });

        MDC.put(RequestId.MDC_KEY, requestId);
        try {
            return chain.filter(exchange.mutate().request(request).build());
        } finally {
            MDC.remove(RequestId.MDC_KEY);
        }
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
