package yumefusaka.envoymart.gateway.filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import yumefusaka.envoymart.common.web.RequestId;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关这一层要保证的是「同一件事在下游叫同一个名字」。
 * <p>
 * 两条容易写错的路径：客户端不带标识时<b>必须由网关补上</b>（否则每个下游各发各的，
 * 跨服务排查依旧断线）；客户端带的值<b>必须先校验再透传</b>（否则一个带换行的头
 * 会顺着网关灌进所有下游的日志）。
 */
class RequestIdGatewayFilterTest {

    private final RequestIdGatewayFilter filter = new RequestIdGatewayFilter();

    @AfterEach
    void tearDown() {
        org.slf4j.MDC.clear();
    }

    /** 跑一遍过滤器，返回下游实际收到的那个 exchange */
    private ServerWebExchange run(MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = seen -> {
            downstream.set(seen);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return downstream.get();
    }

    @Test
    void 客户端没带标识时网关补一个() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/products").build());

        String forwarded = run(exchange).getRequest().getHeaders().getFirst(RequestId.HEADER);

        assertThat(forwarded).isNotNull().hasSize(16);
    }

    @Test
    void 客户端带的合法标识原样透传() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/products").header(RequestId.HEADER, "client-42").build());

        assertThat(run(exchange).getRequest().getHeaders().getFirst(RequestId.HEADER)).isEqualTo("client-42");
    }

    @Test
    void 非法标识被换掉_不会带着换行流向下游() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/products").header(RequestId.HEADER, "bad\nvalue").build());

        String forwarded = run(exchange).getRequest().getHeaders().getFirst(RequestId.HEADER);

        assertThat(forwarded).hasSize(16).doesNotContain("bad");
    }

    @Test
    void 下游只看到一个标识_客户端的值不会与注入的并存() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/products").header(RequestId.HEADER, "client-42").build());

        assertThat(run(exchange).getRequest().getHeaders().get(RequestId.HEADER)).containsExactly("client-42");
    }

    @Test
    void 响应头回写同一个标识() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/products").header(RequestId.HEADER, "client-42").build());

        run(exchange);

        assertThat(exchange.getResponse().getHeaders().getFirst(RequestId.HEADER)).isEqualTo("client-42");
    }

    @Test
    void 同步窗口结束后MDC被还掉_下一个请求不会继承() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/products").header(RequestId.HEADER, "client-42").build());

        run(exchange);

        assertThat(RequestId.current()).isNull();
    }

    @Test
    void 过滤器方法体内MDC里读得到标识_拒绝路径的日志才有线索() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/products").header(RequestId.HEADER, "client-42").build());
        AtomicReference<String> duringChain = new AtomicReference<>();

        filter.filter(exchange, seen -> {
            duringChain.set(RequestId.current());
            return Mono.empty();
        }).block();

        assertThat(duringChain.get()).isEqualTo("client-42");
    }
}
