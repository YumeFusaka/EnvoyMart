package yumefusaka.envoymart.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import yumefusaka.envoymart.common.properties.JwtProperties;
import yumefusaka.envoymart.common.util.JwtUtils;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.common.web.InternalAuth;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关身份头的「先剥离、后注入」——本次鉴权改造的安全核心。
 * <p>
 * 客户端完全可以在请求里自带 {@code X-User-Id} / {@code X-User-Role} / {@code X-Internal-Token}。
 * 剥离之前：公开路径把这些头原样透传给下游；认证路径则靠 {@code HttpHeaders.put} 的覆盖语义
 * 才没被伪造值顶掉——测试里用 {@code containsExactly} 把「下游只看到一个值」钉死，
 * 换成任何"追加而非替换"的实现都会失败。
 */
class JwtGatewayFilterTest {

    private static final String SECRET = "envoymart-test-jwt-secret-key-at-least-32-bytes";
    private static final String INTERNAL_TOKEN = "envoymart-test-internal-token-at-least-32-bytes";

    private static final String FORGED_USER_ID = "victim-id";
    private static final String FORGED_ROLE = "ADMIN";
    private static final String FORGED_INTERNAL_TOKEN = "forged-internal-token";

    private JwtGatewayFilter filter;
    private AtomicReference<ServerWebExchange> forwarded;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecretKey(SECRET);
        jwtProperties.setTtl(60_000);
        filter = new JwtGatewayFilter(jwtProperties, INTERNAL_TOKEN);
        forwarded = new AtomicReference<>();
        chain = exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        };
    }

    private String token(String id, String role) {
        return JwtUtils.createToken(SECRET, 60_000, Map.of("id", id, "username", "demo", JwtUtils.CLAIM_ROLE, role));
    }

    private HttpHeaders downstreamHeaders() {
        assertThat(forwarded.get()).as("请求未被转发到下游").isNotNull();
        return forwarded.get().getRequest().getHeaders();
    }

    @Test
    void 认证请求应剥掉客户端身份头并注入token里的身份() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders/1")
                .header("Authorization", "Bearer " + token("u1001", "USER"))
                .header(IdentityHeaderInterceptor.USER_ID_HEADER, FORGED_USER_ID)
                .header(IdentityHeaderInterceptor.USER_ROLE_HEADER, FORGED_ROLE)
                .header(InternalAuth.TOKEN_HEADER, FORGED_INTERNAL_TOKEN)
                .build());

        filter.filter(exchange, chain).block();

        HttpHeaders headers = downstreamHeaders();
        // containsExactly 同时钉死"唯一性"：伪造值必须被剥掉/覆盖，不能以多值形式与注入值共存
        assertThat(headers.get(IdentityHeaderInterceptor.USER_ID_HEADER)).containsExactly("u1001");
        assertThat(headers.get(IdentityHeaderInterceptor.USER_ROLE_HEADER)).containsExactly("USER");
        assertThat(headers.get(InternalAuth.TOKEN_HEADER)).containsExactly(INTERNAL_TOKEN);
    }

    @Test
    void 公开路径上伪造的身份头应被剥离() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/products")
                // 小写写法：HttpHeaders 大小写不敏感，剥离不能只认某一种写法
                .header("x-user-id", FORGED_USER_ID)
                .header(IdentityHeaderInterceptor.USER_ROLE_HEADER, FORGED_ROLE)
                .header(InternalAuth.TOKEN_HEADER, FORGED_INTERNAL_TOKEN)
                .build());

        filter.filter(exchange, chain).block();

        HttpHeaders headers = downstreamHeaders();
        assertThat(headers.get(IdentityHeaderInterceptor.USER_ID_HEADER)).isNull();
        assertThat(headers.get(IdentityHeaderInterceptor.USER_ROLE_HEADER)).isNull();
        assertThat(headers.get(InternalAuth.TOKEN_HEADER)).isNull();
    }

    @Test
    void 缺少令牌应拒绝为401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/orders/1").build());

        StepVerifier.create(filter.filter(exchange, chain))
                .expectErrorMatches(error -> error instanceof ResponseStatusException statusException
                        && statusException.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value())
                .verify();
        assertThat(forwarded.get()).as("被拒绝的请求不应转发到下游").isNull();
    }

    @Test
    void 伪造签名的令牌应拒绝为401() {
        String forged = JwtUtils.createToken("another-secret-key-at-least-32-bytes-long", 60_000, Map.of("id", "u1003"));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders/1")
                .header("Authorization", "Bearer " + forged)
                .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .expectErrorMatches(error -> error instanceof ResponseStatusException statusException
                        && statusException.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value())
                .verify();
    }

    /**
     * 角色 claim 是后加上的：此前签发的 Token 里没有。那种 Token 要求裸放行到「没有角色」，
     * 由下游按 403 拒绝——绝不能在这里补一个默认角色。
     */
    @Test
    void 没有角色claim的旧token不应注入角色头() {
        String oldToken = JwtUtils.createToken(SECRET, 60_000, Map.of("id", "u1003", "username", "admin"));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders/1")
                .header("Authorization", "Bearer " + oldToken)
                .header(IdentityHeaderInterceptor.USER_ROLE_HEADER, FORGED_ROLE)
                .build());

        filter.filter(exchange, chain).block();

        HttpHeaders headers = downstreamHeaders();
        assertThat(headers.get(IdentityHeaderInterceptor.USER_ID_HEADER)).containsExactly("u1003");
        assertThat(headers.get(IdentityHeaderInterceptor.USER_ROLE_HEADER))
                .as("旧 Token 里的角色缺失应当是「没有角色」，而不是客户端伪造的那个")
                .isNull();
    }

    @Test
    void 只允许服务间调用的路径仍按404拒绝() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/products/stock/deduct").build());

        StepVerifier.create(filter.filter(exchange, chain))
                .expectErrorMatches(error -> error instanceof ResponseStatusException statusException
                        && statusException.getStatusCode().value() == HttpStatus.NOT_FOUND.value())
                .verify();
    }

    /**
     * 路径段里的 {@code ;} 参数必须和下游容器一样被剥掉，否则整份排除清单同时失效。
     * <p>
     * 实测过的绕过：匿名请求 {@code GET /products/internal;x/catalog} 经网关时
     * {@code startsWith("/products/internal/")} 在分号处对不上，请求落进「需登录」分支；
     * 而下游 Tomcat 按 Servlet 规范把 {@code ;x} 当作路径参数剥掉，照常路由到内部方法，
     * 于是匿名拿到了全量商品目录。清单里每一项都有一个这样的变体。
     * <p>
     * {@code %3B} 是同一个洞的编码写法：规范化在解码<b>之后</b>做，所以两种写法都要挡住。
     * <p>
     * 路径要用 {@link URI} 直接构造，不能走 {@code MockServerHttpRequest.get(String)}：
     * 那个重载会把模板过一遍 {@code UriComponentsBuilder.encode()}，{@code %3B} 被二次编码成
     * {@code %253B}——一个真实客户端不会发出的路径，测的就不是这条规则了。
     * 真实请求经 {@code getURI().getRawPath()} 进来，和 {@code URI.create} 是同一个形式。
     */
    @Test
    void 路径段内的分号参数不能绕过内部接口排除() {
        for (String path : new String[]{
                "/products/internal;x/catalog",
                "/products/internal%3Bx/catalog",
                "/orders/internal;x/1/ship",
                "/products/stock;x/deduct"}) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.method(HttpMethod.GET, URI.create(path)).build());

            // 手工捕获而不用 assertThatThrownBy：那个断言在环里不渲染 as() 的描述，
            // 报了「Expecting code to raise a throwable」却看不出是四条里的哪一条
            Throwable thrown = null;
            try {
                filter.filter(exchange, chain).block();
            } catch (Throwable error) {
                thrown = error;
            }
            assertThat(thrown).as("路径 %s 应当按内部接口拒绝", path)
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) thrown).getStatusCode().value())
                    .as("路径 %s 的拒绝状态", path)
                    .isEqualTo(HttpStatus.NOT_FOUND.value());
        }
        assertThat(forwarded.get()).as("被拒绝的请求不应转发到下游").isNull();
    }

    /**
     * 剥离之后仍要能认出正常的公开路径——否则上面的修复会把 {@code /products} 也判错。
     * 这一条与上一条是一对：只测「挡住了」会漏掉「挡过头了」。
     */
    @Test
    void 分号参数不影响公开路径的放行() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/products;x").build());

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).as("公开路径应当照常转发").isNotNull();
    }

    /**
     * 管理接口不能靠公开前缀顺带放行。
     * <p>
     * 白名单里有 {@code GET /products/**} 与 {@code GET /categories/**}，而管理接口就挂在这两个
     * 前缀下面。前缀放行意味着一个**不带任何身份**的匿名请求会被判成公开、一路进到服务里，
     * 只剩 {@code AdminGuardInterceptor} 一道防线。这里钉死的是「本来就进不来」，
     * 而不是「进来了但被拦住了」——两道防线不该塌成一道。
     */
    @Test
    void 管理路径不应被公开前缀放行() {
        for (String path : new String[]{
                "/products/admin/spus",
                "/products/admin",
                "/categories/admin",
                "/brands/admin",
                "/orders/admin",
                "/after-sales/admin",
                "/reviews/admin",
                "/auth/admin/users"}) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(path).build());

            Throwable thrown = null;
            try {
                filter.filter(exchange, chain).block();
            } catch (Throwable error) {
                thrown = error;
            }
            assertThat(thrown).as("管理路径 %s 应当要求登录", path)
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) thrown).getStatusCode().value())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }
        assertThat(forwarded.get()).as("被拒绝的请求不应转发到下游").isNull();
    }

    /**
     * 判据必须是「路径段等于 admin」，不能是「路径里含 admin」。
     * <p>
     * 知识库文档编号是自由命名的公开资源（{@code /knowledge/documents/**} 在白名单里），
     * 出现一个叫 {@code admin-guide} 的文档完全正常。用 {@code contains("admin")} 会让这类
     * 公开资源变成要登录——一个为了让管理接口更安全而引入的回归。
     */
    @Test
    void 路径中含admin字样的公开资源不应被误伤() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/knowledge/documents/admin-guide").build());

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).as("文档编号里带 admin 的公开资源应当照常放行").isNotNull();
    }
}
