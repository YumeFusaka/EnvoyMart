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
import yumefusaka.envoymart.gateway.ControllerSources;
import yumefusaka.envoymart.common.util.JwtUtils;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.common.web.InternalAuth;

import java.net.URI;
import java.util.HashMap;
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
    /** 假认证态存储：默认空（等于「没有记录」，绝大多数用户的真实状态），用例按需塞值 */
    private final Map<String, String> authStates = new HashMap<>();

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecretKey(SECRET);
        jwtProperties.setTtl(60_000);
        authStates.clear();
        filter = new JwtGatewayFilter(jwtProperties, INTERNAL_TOKEN,
                userId -> Mono.justOrEmpty(authStates.get(userId)));
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
        // 这几条都是从 INTERNAL_ONLY_PREFIXES 里现挑的。原先其中一条是
        // /orders/internal;x/1/ship —— 发货搬到 /orders/admin 之后那条前缀就没了，
        // 于是它不再被按「内部接口」拒绝，而是掉进「需要登录」拿到 401，
        // 断言 404 便失败了。**这不是测试写错了，是它跟着清单一起过期了**：
        // 内部接口的清单变了，测它的样例就得跟着换。
        for (String path : new String[]{
                "/products/internal;x/catalog",
                "/products/internal%3Bx/catalog",
                "/payments/internal;x/refund",
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
     * <p>
     * 路径清单<b>扫源码得出</b>而不是手写：原先这份数组是硬编码的，新加
     * {@code /tickets/admin}、{@code /ai/admin} 时没人回来补，测试照样绿——
     * 而它守的恰恰是"每个管理入口都进不来"。加接口时漏一次登记，正是这条测试存在的理由。
     */
    @Test
    void 管理路径不应被公开前缀放行() {
        var adminPaths = ControllerSources.adminPaths();
        assertThat(adminPaths)
                .as("一个管理路径都没扫到，源码扫描的路径推算错了——这条测试会变成永远通过的空壳")
                .hasSizeGreaterThan(5);

        for (String path : adminPaths) {
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

    /**
     * 商品评价匿名可读：详情页公开，评价是详情的一部分，
     * 「详情能看、评价要登录」是自相矛盾的。
     * <p>
     * 但**提交**评价与标记有用（POST）仍然要求登录，管理端的评价接口在 /admin 段下，
     * 已被 {@link #管理路径不应被公开前缀放行} 扫源码自动纳入。
     */
    @Test
    void 商品评价列表应匿名放行而提交仍要求登录() {
        MockServerWebExchange read = MockServerWebExchange.from(
                MockServerHttpRequest.get("/reviews/spu/12").build());
        filter.filter(read, chain).block();
        assertThat(forwarded.get()).as("评价列表应当照常转发").isNotNull();

        MockServerWebExchange stat = MockServerWebExchange.from(
                MockServerHttpRequest.get("/reviews/spu/12/statistics").build());
        filter.filter(stat, chain).block();
        assertThat(forwarded.get()).as("评价统计应当照常转发").isNotNull();

        forwarded.set(null);
        MockServerWebExchange write = MockServerWebExchange.from(
                MockServerHttpRequest.post("/reviews").build());
        Throwable thrown = null;
        try {
            filter.filter(write, chain).block();
        } catch (Throwable error) {
            thrown = error;
        }
        assertThat(thrown).as("匿名提交评价应当要求登录").isInstanceOf(ResponseStatusException.class);
        assertThat(forwarded.get()).as("被拒绝的请求不应转发到下游").isNull();
    }

    /**
     * 被禁用的账号，手里的旧 Token 立刻失效。
     * <p>
     * 这是本批次存在的理由：JWT 自证，签发之后服务端管不着它了。没有这条检查，
     * 「禁用用户」就只是改了个数据库字段，被禁用的人照样能下单、能看订单，界面上一切正常。
     */
    @Test
    void 已禁用用户的旧token应立即被拒() {
        authStates.put("u1001", "0|USER");
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders/1")
                .header("Authorization", "Bearer " + token("u1001", "USER"))
                .build());

        Throwable thrown = null;
        try {
            filter.filter(exchange, chain).block();
        } catch (Throwable error) {
            thrown = error;
        }

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode().value())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(forwarded.get()).as("被拒绝的请求不应转发到下游").isNull();
    }

    /**
     * 角色以认证态为准，Token 里那个只是签发时刻的快照。
     * <p>
     * 两个方向都要钉死：降权之后不能还顶着 ADMIN 继续管人，提权之后也不该等到重新登录才生效。
     */
    @Test
    void 角色应以认证态为准而不是token里的快照() {
        // Token 说 USER，实际已经是 ADMIN（刚提权，手里的票还没换）
        authStates.put("u1001", "1|ADMIN");
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/orders/1")
                .header("Authorization", "Bearer " + token("u1001", "USER"))
                .build()), chain).block();
        assertThat(downstreamHeaders().get(IdentityHeaderInterceptor.USER_ROLE_HEADER))
                .containsExactly("ADMIN");

        // Token 说 ADMIN，实际已经被降成 USER（刚被撤权，手里的票还没过期）——
        // 这一侧才是安全相关的那一侧
        setUp();
        authStates.put("u1002", "1|USER");
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/orders/1")
                .header("Authorization", "Bearer " + token("u1002", "ADMIN"))
                .build()), chain).block();
        assertThat(downstreamHeaders().get(IdentityHeaderInterceptor.USER_ROLE_HEADER))
                .containsExactly("USER");
    }

    /**
     * 认证态读不到时必须退回「按 Token 放行」的旧行为。
     * <p>
     * 这条覆盖的是 Redis 挂掉 / 被清空 / 没配好：绝大多数用户在认证态存储里本来就没有记录，
     * 若把「查不到」当成「有问题」，表现会是全站用户突然都登不上——那是比它要防的问题更大的事故。
     */
    @Test
    void 认证态读不到时应按token放行() {
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/orders/1")
                .header("Authorization", "Bearer " + token("u1001", "USER"))
                .build()), chain).block();

        assertThat(downstreamHeaders().get(IdentityHeaderInterceptor.USER_ID_HEADER))
                .containsExactly("u1001");
        assertThat(downstreamHeaders().get(IdentityHeaderInterceptor.USER_ROLE_HEADER))
                .containsExactly("USER");
    }
}
