package yumefusaka.envoymart.common.web;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.Retryer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 瞬时失败重试的判据。
 * <p>
 * 这套判据的核心不是「失败会重试」，而是「什么失败<b>不许</b>重试」——
 * 写请求在状态未知时被重试，就是把一次取消变成两次副作用。每个放行分支
 * 都配一条对应的拒绝断言。
 */
class InternalFeignRetryConfigTest {

    private static final InternalFeignRetryConfig.TransientErrorDecoder DECODER =
            new InternalFeignRetryConfig.TransientErrorDecoder();

    private static Request request(Request.HttpMethod method) {
        return Request.create(method, "http://order-service/orders/1", Map.of(),
                Request.Body.create(new byte[0]), null);
    }

    private static Response response(int status, Request.HttpMethod method) {
        return Response.builder()
                .status(status)
                .reason("test")
                .request(request(method))
                .headers(Map.of())
                .build();
    }

    /** 模拟 Feign 对连接类 IO 失败的处理：errorExecuting 产出 status=-1、cause 为原始 IOException */
    private static RetryableException ioFailure(Request.HttpMethod method, IOException cause) {
        return new RetryableException(-1, cause.getMessage(), method, cause, (Long) null, request(method));
    }

    // ──── ErrorDecoder：哪些响应值得再试一次 ────

    @Test
    void 服务端明确不可用时读写请求都转成可重试() {
        assertThat(DECODER.decode("m", response(503, Request.HttpMethod.GET)))
                .isInstanceOf(RetryableException.class);
        assertThat(DECODER.decode("m", response(503, Request.HttpMethod.POST)))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void 网关类失败只重试幂等的GET() {
        assertThat(DECODER.decode("m", response(504, Request.HttpMethod.GET)))
                .isInstanceOf(RetryableException.class);
        // 写请求的 504：下游可能已处理、只是响应丢了，重试可能变成第二次副作用
        assertThat(DECODER.decode("m", response(504, Request.HttpMethod.POST)))
                .isNotInstanceOf(RetryableException.class);
        assertThat(DECODER.decode("m", response(502, Request.HttpMethod.POST)))
                .isNotInstanceOf(RetryableException.class);
    }

    @Test
    void 业务失败与500保持原有语义不重试() {
        // 500 是业务代码执行到一半崩了，状态未知——重试是在赌
        assertThat(DECODER.decode("m", response(500, Request.HttpMethod.GET)))
                .isInstanceOf(FeignException.class)
                .isNotInstanceOf(RetryableException.class);
        // 4xx 的语义完全走默认解码器，不受本配置影响
        assertThat(DECODER.decode("m", response(404, Request.HttpMethod.GET)))
                .isInstanceOf(FeignException.class)
                .isNotInstanceOf(RetryableException.class);
    }

    // ──── Retryer：可重试异常里哪些真的重试 ────

    @Test
    void 连接被拒任何方法都重试且两次后放弃() {
        Retryer retryer = new InternalFeignRetryConfig.TransientRetryer();
        RetryableException refused = ioFailure(Request.HttpMethod.POST, new ConnectException("Connection refused"));

        // 抛出即代表「放弃并上抛」。Retryer.Default 的 maxAttempts=3 语义是「总共 3 次尝试」：
        // 首次 + 2 次重试后仍失败，第 3 次 continueOrPropagate 直接上抛
        assertThatCode(() -> retryer.continueOrPropagate(refused)).doesNotThrowAnyException();
        assertThatCode(() -> retryer.continueOrPropagate(refused)).doesNotThrowAnyException();
        assertThatThrownBy(() -> retryer.continueOrPropagate(refused))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void 读写超时只重试GET而写请求立即上抛() {
        assertThatThrownBy(() -> new InternalFeignRetryConfig.TransientRetryer()
                .continueOrPropagate(ioFailure(Request.HttpMethod.POST, new SocketTimeoutException("Read timed out"))))
                .isInstanceOf(RetryableException.class);

        assertThatCode(() -> new InternalFeignRetryConfig.TransientRetryer()
                .continueOrPropagate(ioFailure(Request.HttpMethod.GET, new SocketTimeoutException("Read timed out"))))
                .doesNotThrowAnyException();
    }

    @Test
    void 两道配置接力后503写请求进入重试() {
        // 解码器负责「哪些响应值得试」，重试器负责「哪些异常真的试」——两层判定接力后，
        // 503 的写请求整条路都放行（与 ioFailure 直造的异常不同，这里验证的是真实接力形态）
        RetryableException wrapped = (RetryableException) DECODER.decode("m", response(503, Request.HttpMethod.POST));

        assertThatCode(() -> new InternalFeignRetryConfig.TransientRetryer().continueOrPropagate(wrapped))
                .doesNotThrowAnyException();
    }

    @Test
    void 带RetryAfter的429不跟() {
        // 默认解码器对带 Retry-After 的响应也会产出可重试异常——不遵守 Retry-After 的快速重试
        // 只会加重限流方负担，这里必须拒绝
        Response limited = Response.builder()
                .status(429)
                .reason("Too Many Requests")
                .request(request(Request.HttpMethod.POST))
                .headers(Map.of("Retry-After", List.of("30")))
                .build();
        RetryableException decoded = (RetryableException) DECODER.decode("m", limited);

        assertThatThrownBy(() -> new InternalFeignRetryConfig.TransientRetryer().continueOrPropagate(decoded))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void 克隆后安全判据仍在() {
        // 回归断言：父类 Retryer.Default.clone() 返回的是普通 Default，不覆写 clone 的话
        // 每次真实调用（Feign 逐请求 clone）都会退化成无差别重试——这条测试专门钉住它
        Retryer cloned = new InternalFeignRetryConfig.TransientRetryer().clone();
        assertThat(cloned).isInstanceOf(InternalFeignRetryConfig.TransientRetryer.class);

        assertThatThrownBy(() -> cloned.continueOrPropagate(
                ioFailure(Request.HttpMethod.POST, new SocketTimeoutException("Read timed out"))))
                .isInstanceOf(RetryableException.class);
    }
}
