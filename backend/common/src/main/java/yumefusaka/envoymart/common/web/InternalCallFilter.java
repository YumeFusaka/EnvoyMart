package yumefusaka.envoymart.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 服务间调用的身份校验 —— 决定「要不要相信这个 {@code X-User-Id}」。
 * <p>
 * <b>它防的是什么</b>：网关校验 JWT 后把用户 ID 透传到 {@code X-User-Id}，下游无条件信任它。
 * 但下游服务的端口是直接监听的（本地 9001-9006），<b>绕过网关直连就能伪造任意身份</b>——
 * 实测无 token 直连 9003、带上一个 {@code X-User-Id} 头，就能读到他人订单的收货地址与电话。
 * <p>
 * <b>判定的关键设计：只在请求「声称有身份」时才校验。</b>
 * <ul>
 *   <li>没带 {@code X-User-Id} → 放行。这条请求没有冒充任何人——公开接口，以及 MCP 端点
 *       （它走自己的 JWT / API Key 认证，不经过网关）都属于这一类；</li>
 *   <li>带了且令牌正确 → 放行，身份可信；</li>
 *   <li>带了但令牌不对或缺失 → <b>拒绝</b>。声称自己是某个用户却拿不出凭证，这只能是伪造。</li>
 * </ul>
 * 反过来写（"所有请求都必须带令牌"）会把 MCP 端点一起挡掉，还得再维护一份路径白名单；
 * 按"有没有声称身份"来判，规则少一条，也不会随新增接口而失效。
 * <p>
 * <b>为什么拒绝而不是静默摘掉身份头</b>：摘掉之后下游会在 {@code @RequestHeader} 上抛
 * 「缺少请求头」，那个报错看不出是配置漏了还是有人在直连；401 加一句明确提示能让人立刻定位。
 */
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InternalCallFilter extends OncePerRequestFilter {

    private final String expectedToken;

    public InternalCallFilter(@Value("${envoymart.internal.token:}") String expectedToken) {
        this.expectedToken = InternalAuth.requireValid(expectedToken);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String userId = request.getHeader(IdentityHeaderInterceptor.USER_ID_HEADER);
        if (userId == null || userId.isBlank()) {
            // 没有声称身份，无需校验
            chain.doFilter(request, response);
            return;
        }
        if (expectedToken.equals(request.getHeader(InternalAuth.TOKEN_HEADER))) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("[InternalAuth] 拒绝声称了身份却拿不出服务间令牌的请求 path={} remote={} claimedUserId={}",
                request.getRequestURI(), request.getRemoteAddr(), userId);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"缺少服务间调用凭证\"}");
    }
}
