package yumefusaka.envoymart.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import yumefusaka.envoymart.common.context.BaseContext;

/**
 * 把网关透传的身份头放进 {@link BaseContext}，供当前请求内的下游代码取用。
 * <p>
 * <b>只在请求确实带了该头时才接管。</b>没有头的时候既不能写入（会用一个 null 覆盖掉
 * 别处已经放好的身份），也不能在收尾时清理（会清掉不属于自己的值）。
 * <p>
 * 这条约束来自一个实测故障：MCP 端点由 {@code McpAuthFilter} 校验 JWT 后把身份放进
 * BaseContext，而本拦截器的 {@code preHandle} 随即用 {@code X-User-Id}（MCP 客户端不传）
 * 把它覆盖成 null、{@code afterCompletion} 又清一次——同一个 ThreadLocal 被两个组件按
 * 不同语义争夺，表现为工具以"缺少经过认证的用户身份"失败。
 * <p>
 * 用请求属性记录「这次是不是我设的」，而不是靠实例字段——拦截器是单例，不能持有请求级状态。
 */
public class IdentityHeaderInterceptor implements HandlerInterceptor {

    public static final String USER_ID_HEADER = "X-User-Id";

    /** 标记本次请求的身份由本拦截器写入，收尾时据此决定是否清理 */
    private static final String OWNED_ATTRIBUTE = IdentityHeaderInterceptor.class.getName() + ".owned";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader(USER_ID_HEADER);
        if (userId == null || userId.isBlank()) {
            return true;
        }
        BaseContext.setCurrentId(userId);
        request.setAttribute(OWNED_ATTRIBUTE, Boolean.TRUE);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (Boolean.TRUE.equals(request.getAttribute(OWNED_ATTRIBUTE))) {
            BaseContext.removeCurrentId();
        }
    }
}
