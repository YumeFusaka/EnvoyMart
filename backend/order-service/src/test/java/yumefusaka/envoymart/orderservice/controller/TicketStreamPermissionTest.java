package yumefusaka.envoymart.orderservice.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@code GET /tickets/stream} 的身份边界。
 *
 * <p>这条端点与其他工单接口共用同一套身份语义（网关身份头 + 服务间令牌），但它是
 * <b>长连接</b>：一旦在鉴权上出了口子，泄露的不是一次响应而是持续推送的工单数据 ——
 * 所以把它单独钉一遍，而不是假设「和别的接口一样」。
 *
 * <p>三条断言对应三种身份状态：<b>匿名</b>（无身份头，拿不到流）、
 * <b>伪造</b>（带了身份头却没有服务间令牌，被 InternalCallFilter 判为冒充 → 401）、
 * <b>可信</b>（身份头 + 令牌齐备，连接建立并收到本用户的初始快照）。
 *
 * <p><b>这是一个端到端测试</b>（与 {@code StockConcurrencyTest} 同一形态）：它直接
 * 对真实的 order-service 发 HTTP，因此需要该服务 + 服务间令牌，
 * 用下面两个 {@code -D} 显式打开，不进 CI —— 长连接的行为无法在没有服务进程的构建机上复现：
 * <pre>
 *   mvn -pl order-service test -Dtest=TicketStreamPermissionTest \
 *       -Drun.ticketStreamTest=true -DinternalToken=&lt;INTERNAL_TOKEN&gt;
 * </pre>
 *
 * <p>用系统属性而不是环境变量作开关：环境变量在 surefire 派生出来的 JVM 里要额外配置
 * 才能透传，而 `-D` 是 Maven 一路传到底的，少一处会静默失效的接缝。
 *
 * <p>可选环境变量：{@code ORDER_SERVICE_URL}（默认 127.0.0.1:9003）。
 */
@EnabledIfSystemProperty(named = "run.ticketStreamTest", matches = "true")
class TicketStreamPermissionTest {

    private static final String ORDER_URL =
            System.getenv().getOrDefault("ORDER_SERVICE_URL", "http://127.0.0.1:9003");
    /**
     * 服务间令牌。优先取系统属性（`-DinternalToken=...`，Maven 一路传得进去），
     * 其次取环境变量（开发机上恰好 export 过时也好使）。
     */
    private static final String INTERNAL_TOKEN =
            System.getProperty("internalToken", System.getenv("INTERNAL_TOKEN"));

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 发一次 GET。**只给会被同步拒绝的那两个用例用** —— 它们的响应体有界、会正常收尾，
     * `ofString()` 读得完。成功建立的 SSE 不能用它：那是一条永不结束的流，
     * `ofString()` 会一直等到读超时，把一个本该 2 秒结束的用例拖成几分钟（实测挂死）。
     */
    private HttpResponse<String> getRejected(String userIdHeader, boolean withToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(ORDER_URL + "/tickets/stream"))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(3))
                .GET();
        if (userIdHeader != null) {
            builder.header("X-User-Id", userIdHeader);
        }
        if (withToken) {
            builder.header("X-Internal-Token", INTERNAL_TOKEN);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void 匿名访问流式端点被挡在身份校验之外() throws Exception {
        HttpResponse<String> response = getRejected(null, false);

        assertThat(response.statusCode())
                .as("没有身份头就不该建立起一条 SSE 长连接")
                .isNotEqualTo(200);
        assertThat(response.body()).doesNotContain("awaiting");
    }

    @Test
    void 伪造身份头但没有服务间令牌被拒绝() throws Exception {
        HttpResponse<String> response = getRejected("u1001", false);

        assertThat(response.statusCode())
                .as("声称了身份却拿不出凭证，只能是伪造")
                .isEqualTo(401);
        assertThat(response.body()).doesNotContain("awaiting");
    }

    /**
     * 可信身份：连接建立、并收到初始快照帧。
     * <p>
     * 用「读流 + 硬截止」而不是 `ofString()`：SSE 没有结束条件，读到目标帧（或时限到）
     * 就停。`assertTimeoutPreemptively` 是第二道保险 —— 万一底层读卡住，这个用例也必须
     * 在限定时间内结束，而不是把整个构建挂住。
     */
    @Test
    void 可信身份才能建立连接并收到本人快照() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ORDER_URL + "/tickets/stream"))
                    .header("Accept", "text/event-stream")
                    .header("X-User-Id", "u1001")
                    .header("X-Internal-Token", INTERNAL_TOKEN)
                    .GET()
                    .build();
            HttpResponse<java.io.InputStream> response =
                    HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).as("可信身份应建立 SSE 连接").isEqualTo(200);
            // 初始快照是注册后立刻推的第一帧（见 SupportTicketController#stream）；
            // 读到含 awaiting 的内容即证明鉴权通过、且走的是与 /tickets/summary 相同的判据
            StringBuilder seen = new StringBuilder();
            byte[] buf = new byte[256];
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            try (java.io.InputStream body = response.body()) {
                while (System.nanoTime() < deadline && !seen.toString().contains("awaiting")) {
                    int n = body.read(buf);
                    if (n < 0) break;
                    seen.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            assertThat(seen.toString())
                    .as("连上后应先收到一帧 awaiting 初始快照")
                    .contains("awaiting");
        });
    }
}
