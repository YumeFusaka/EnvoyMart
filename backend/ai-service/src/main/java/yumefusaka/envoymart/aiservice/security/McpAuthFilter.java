package yumefusaka.envoymart.aiservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import yumefusaka.envoymart.common.util.JwtUtils;

import java.io.IOException;

/**
 * MCP 端点鉴权。
 * <p>
 * MCP 工具能读订单、查物流、搜商品，裸露的 /mcp 等于把业务接口开给任意进程。
 * 这里要求二选一的凭证：
 * <ul>
 *   <li>平台 JWT（Authorization: Bearer xxx），与网关鉴权一致；</li>
 *   <li>静态 API Key（X-MCP-API-Key: xxx），供无法走登录流程的机器客户端使用。</li>
 * </ul>
 * 未配置 API Key 时只接受 JWT，避免出现"默认弱口令"。
 */
@Slf4j
public class McpAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-MCP-API-Key";

    private final String jwtSecret;
    private final String apiKey;

    public McpAuthFilter(String jwtSecret, String apiKey) {
        this.jwtSecret = jwtSecret;
        this.apiKey = apiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (authenticated(request)) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("[MCP] unauthorized request from {} {}", request.getRemoteAddr(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // 用 JSON-RPC 错误体返回，便于 MCP 客户端解析
        response.getWriter().write("""
                {"jsonrpc":"2.0","error":{"code":-32001,"message":"Unauthorized: 缺少有效的 JWT 或 X-MCP-API-Key"},"id":null}""");
    }

    private boolean authenticated(HttpServletRequest request) {
        if (apiKey != null && !apiKey.isBlank()) {
            String provided = request.getHeader(API_KEY_HEADER);
            if (apiKey.equals(provided)) {
                return true;
            }
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        try {
            JwtUtils.parseToken(jwtSecret, authorization.substring(7));
            return true;
        } catch (Exception e) {
            log.debug("[MCP] jwt rejected: {}", e.getMessage());
            return false;
        }
    }
}
