package yumefusaka.envoymart.authservice;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.authservice.entity.UserEntity;
import yumefusaka.envoymart.authservice.mapper.UserMapper;
import yumefusaka.envoymart.common.util.JwtUtils;
import yumefusaka.envoymart.common.web.AdminGuardInterceptor;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.common.web.InternalAuth;
import yumefusaka.envoymart.common.web.RequireAdmin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理员鉴权的整体链路（服务内）。
 * <p>
 * 起的是真实 Tomcat（随机端口）与真实上下文：InternalCallFilter、AdminGuardInterceptor、
 * H2 里由 data.sql 种下的管理员账号，全部按生产装配生效，请求走真实 HTTP。
 * 测试自己扮演网关——把登录拿到的 JWT 按网关的规则还原成请求头，
 * 于是「角色 claim → 身份头 → 守卫判定」整条链路都被覆盖；加真实网关进程的那一遍
 * 走单独的端到端验证。
 * <p>
 * 最关键的一条是 {@link #伪造身份头直连服务端口应被拒绝()}：它证明客户端自己塞
 * {@code X-User-Role: ADMIN} 不管用——服务只信任带内部令牌的身份头。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "JWT_SECRET=" + AdminAccessControlTest.SECRET,
        "INTERNAL_TOKEN=" + AdminAccessControlTest.INTERNAL_TOKEN
})
@Import(AdminAccessControlTest.TestAdminController.class)
class AdminAccessControlTest {

    static final String SECRET = "envoymart-test-jwt-secret-key-at-least-32-bytes";
    static final String INTERNAL_TOKEN = "envoymart-test-internal-token-at-least-32-bytes";

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** 验证用的管理接口，替代真实管理后台（它属于后续批次，不在本模块） */
    @RequireAdmin
    @RestController
    static class TestAdminController {

        @GetMapping("/__test/admin/ping")
        String ping() {
            return "pong";
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private UserMapper userMapper;

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String login(String username, String password) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", StandardCharsets.UTF_8))
                .build());
        assertThat(response.statusCode()).as("登录失败：%s", response.body()).isEqualTo(200);
        Matcher matcher = TOKEN_PATTERN.matcher(response.body());
        assertThat(matcher.find()).as("登录响应里没有 token：%s", response.body()).isTrue();
        return matcher.group(1);
    }

    private Claims claims(String token) {
        return JwtUtils.parseToken(SECRET, token);
    }

    /** 按网关的规则还原请求：身份与角色都取自 JWT，外加服务间令牌 */
    private HttpRequest gatewayRequest(String path, String token) {
        Claims claims = claims(token);
        return HttpRequest.newBuilder(uri(path))
                .header(IdentityHeaderInterceptor.USER_ID_HEADER, String.valueOf(claims.get("id")))
                .header(IdentityHeaderInterceptor.USER_ROLE_HEADER, String.valueOf(claims.get(JwtUtils.CLAIM_ROLE)))
                .header(InternalAuth.TOKEN_HEADER, INTERNAL_TOKEN)
                .GET()
                .build();
    }

    @Test
    void 管理员种子账号可以登录且角色为ADMIN() throws Exception {
        String token = login("admin", "123456");

        assertThat(claims(token).get(JwtUtils.CLAIM_ROLE)).isEqualTo(AdminGuardInterceptor.ADMIN_ROLE);
        assertThat(claims(token).get("id")).isEqualTo("u1003");
    }

    @Test
    void 普通用户登录签发的角色为USER() throws Exception {
        String token = login("alice", "123456");

        assertThat(claims(token).get(JwtUtils.CLAIM_ROLE)).isEqualTo("USER");
        assertThat(claims(token).get("id")).isEqualTo("u1001");
    }

    @Test
    void 注册的新用户角色为USER且与库中一致() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"guard_test\",\"password\":\"pass1234\",\"nickname\":\"鉴权测试\"}",
                        StandardCharsets.UTF_8))
                .build());
        assertThat(response.statusCode()).as("注册失败：%s", response.body()).isEqualTo(200);

        Matcher matcher = TOKEN_PATTERN.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        String token = matcher.group(1);
        assertThat(claims(token).get(JwtUtils.CLAIM_ROLE)).isEqualTo("USER");

        // 角色的事实来源是数据库：claim 必须与 sys_user.role_name 一致
        UserEntity saved = userMapper.selectById(String.valueOf(claims(token).get("id")));
        assertThat(saved.getRoleName()).isEqualTo("USER");
    }

    @Test
    void 未携带身份头访问管理接口应返回401() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/__test/admin/ping")).GET().build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void 普通用户token经网关语义访问管理接口应返回403() throws Exception {
        HttpResponse<String> response = send(gatewayRequest("/__test/admin/ping", login("alice", "123456")));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":403");
    }

    @Test
    void 管理员token经网关语义访问管理接口应返回200() throws Exception {
        HttpResponse<String> response = send(gatewayRequest("/__test/admin/ping", login("admin", "123456")));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("pong");
    }

    /**
     * 没有服务间令牌的「身份头」只能来自伪造。网关之外的调用方拿不到内部令牌，
     * 所以直连服务端口伪造身份 + 角色的请求必须被 InternalCallFilter 挡在守卫之前。
     */
    @Test
    void 伪造身份头直连服务端口应被拒绝() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/__test/admin/ping"))
                .header(IdentityHeaderInterceptor.USER_ID_HEADER, "u1003")
                .header(IdentityHeaderInterceptor.USER_ROLE_HEADER, AdminGuardInterceptor.ADMIN_ROLE)
                .GET()
                .build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void 只伪造角色头也应被拒绝() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/__test/admin/ping"))
                .header(IdentityHeaderInterceptor.USER_ROLE_HEADER, AdminGuardInterceptor.ADMIN_ROLE)
                .GET()
                .build());

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
