package yumefusaka.envoymart.common.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理接口守卫的行为契约。
 * <p>
 * 最要紧的两条：401 与 403 语义必须分开（前者该重新登录，后者重试无用）；
 * 角色头单独出现不作数（客户端能自己塞 {@code X-User-Role: ADMIN}，
 * 没有身份头时它只能是伪造）。
 */
class AdminGuardInterceptorTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminProbe(), new OpenProbe(), new ClassLevelAdminProbe())
                .addInterceptors(new AdminGuardInterceptor())
                .build();
    }

    @RestController
    static class AdminProbe {

        @RequireAdmin
        @GetMapping("/admin/ping")
        String ping() {
            return "pong";
        }
    }

    @RestController
    static class OpenProbe {

        @GetMapping("/open/ping")
        String ping() {
            return "ok";
        }
    }

    @RequireAdmin
    @RestController
    static class ClassLevelAdminProbe {

        @GetMapping("/admin/class-level")
        String ping() {
            return "pong";
        }
    }

    @Test
    void 未登录访问管理接口应返回401() throws Exception {
        mockMvc.perform(get("/admin/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void 已登录的普通用户访问管理接口应返回403() throws Exception {
        mockMvc.perform(get("/admin/ping")
                        .header(IdentityHeaderInterceptor.USER_ID_HEADER, "u1001")
                        .header(IdentityHeaderInterceptor.USER_ROLE_HEADER, "USER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void 管理员访问管理接口应放行() throws Exception {
        mockMvc.perform(get("/admin/ping")
                        .header(IdentityHeaderInterceptor.USER_ID_HEADER, "u1003")
                        .header(IdentityHeaderInterceptor.USER_ROLE_HEADER, AdminGuardInterceptor.ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(content().string("pong"));
    }

    @Test
    void 缺少角色头时按403拒绝() throws Exception {
        mockMvc.perform(get("/admin/ping")
                        .header(IdentityHeaderInterceptor.USER_ID_HEADER, "u1001"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 只有伪造的角色头而不带身份头时按未登录处理() throws Exception {
        mockMvc.perform(get("/admin/ping")
                        .header(IdentityHeaderInterceptor.USER_ROLE_HEADER, AdminGuardInterceptor.ADMIN_ROLE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 角色值不做模糊匹配() throws Exception {
        mockMvc.perform(get("/admin/ping")
                        .header(IdentityHeaderInterceptor.USER_ID_HEADER, "u1001")
                        .header(IdentityHeaderInterceptor.USER_ROLE_HEADER, "admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 未标注注解的接口不受守卫影响() throws Exception {
        mockMvc.perform(get("/open/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void 类级注解同样生效() throws Exception {
        mockMvc.perform(get("/admin/class-level"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/class-level")
                        .header(IdentityHeaderInterceptor.USER_ID_HEADER, "u1003")
                        .header(IdentityHeaderInterceptor.USER_ROLE_HEADER, AdminGuardInterceptor.ADMIN_ROLE))
                .andExpect(status().isOk());
    }
}
