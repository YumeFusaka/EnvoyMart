package yumefusaka.envoymart.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 管理接口守卫 —— 只对标注了 {@link RequireAdmin} 的处理器生效。
 * <p>
 * <b>判定只看网关透传的请求头，不读任何用户可写的数据</b>（请求体、查询参数、路径变量都不参与）：
 * <ul>
 *   <li>没有 {@code X-User-Id} → 401。请求根本没声称身份，谈不上"有没有权限"；</li>
 *   <li>有身份但没有 {@code X-User-Role: ADMIN} → 403。身份可信但角色不够，语义与 401 必须分开——
 *       401 该去重新登录，403 重试多少次都一样。</li>
 * </ul>
 * <p>
 * <b>身份为什么读请求头而不是 {@code BaseContext}</b>：BaseContext 可能由别的认证入口写入
 * （如 ai-service 的 MCP 过滤器验完自己的凭证后直接 setCurrentId），那条路径不经过
 * {@code InternalCallFilter} 的「声称身份就必须带服务间令牌」检查，而 {@code X-User-Id} 头
 * 只有过了那个检查才可能出现。守卫跟着请求头走，等于自动继承那份检查，
 * 少一处「以后新增认证入口时要记得同步」的隐含依赖。
 * <p>
 * <b>角色头本身不可单独信任</b>：客户端可以自带 {@code X-User-Role: ADMIN}。两件事让它不成立——
 * 网关会先剥掉入站的身份头再注入自己那份（{@code JwtGatewayFilter}），
 * 而直连服务端口时，「带了 X-User-Id 却没有服务间令牌」的请求会被 {@code InternalCallFilter}
 * 挡成 401。伪造角色头且不带身份头 → 这里按未登录处理；两个头都伪造 → 上游 401。
 */
@Slf4j
public class AdminGuardInterceptor implements HandlerInterceptor {

    /** 管理员角色值。与 auth-service 里 {@code sys_user.role_name} 的取值一致 */
    public static final String ADMIN_ROLE = "ADMIN";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // 静态资源、错误页等非控制器处理器没有注解可读，不属于"标注过的接口"
        if (!(handler instanceof HandlerMethod handlerMethod) || !requiresAdmin(handlerMethod)) {
            return true;
        }

        String userId = request.getHeader(IdentityHeaderInterceptor.USER_ID_HEADER);
        if (userId == null || userId.isBlank()) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录");
            return false;
        }
        if (!ADMIN_ROLE.equals(request.getHeader(IdentityHeaderInterceptor.USER_ROLE_HEADER))) {
            log.warn("[AdminGuard] 拒绝非管理员访问管理接口 path={} userId={}",
                    request.getRequestURI(), userId);
            reject(response, HttpServletResponse.SC_FORBIDDEN, "需要管理员权限");
            return false;
        }
        return true;
    }

    private boolean requiresAdmin(HandlerMethod handlerMethod) {
        // 方法与类都查：类级注解覆盖整个控制器，方法级注解覆盖单个接口
        return AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), RequireAdmin.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), RequireAdmin.class);
    }

    /**
     * 直接写响应而不是抛异常：拦截器抛出的异常会走 {@code @RestControllerAdvice} 的兜底分支，
     * 被换成 HTTP 200 + code=500 的业务返回，401/403 就没法被调用方或网关照常识别了。
     * 响应体沿用项目约定（与 {@code InternalCallFilter}、网关错误出口一致）。
     */
    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"msg\":\"" + message + "\"}");
    }
}
