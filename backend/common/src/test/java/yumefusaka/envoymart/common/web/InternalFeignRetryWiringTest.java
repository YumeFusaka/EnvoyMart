package yumefusaka.envoymart.common.web;

import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.RequestLine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重试在真实 Feign 调用环路里的接线 —— 判据函数的单测回答了「什么该重试」，
 * 这里回答「判据真的接进了 Feign 的循环吗」。
 * <p>
 * 两件事只有在真实环路里才验得到：
 * 1. {@code RetryableException} 确实沿 Feign 的异常通道到达 Retryer（而不是被别的层截住）；
 * 2. Feign 逐请求 {@code clone()} Retryer ——安全判据在克隆体上仍然生效（单测里
 *    {@link InternalFeignRetryConfigTest#克隆后安全判据仍在} 钉的是 clone 本身，这里钉的是
 *    「真实调用走的就是这条 clone 路径」）。
 * <p>
 * 用 JDK 自带的 HttpServer 当桩，不引入任何测试依赖。
 */
class InternalFeignRetryWiringTest {

    interface StubApi {
        @RequestLine("GET /flaky")
        String flaky();
    }

    @Test
    void 下游先503后恢复时真实调用被重试且返回成功() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/flaky", exchange -> {
            int n = hits.incrementAndGet();
            byte[] body = ("hit-" + n).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(n == 1 ? 503 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            StubApi api = Feign.builder()
                    .errorDecoder(new InternalFeignRetryConfig.TransientErrorDecoder())
                    .retryer(new InternalFeignRetryConfig.TransientRetryer())
                    .target(StubApi.class, "http://127.0.0.1:" + server.getAddress().getPort());

            assertThat(api.flaky()).isEqualTo("hit-2");
            assertThat(hits.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }
}
