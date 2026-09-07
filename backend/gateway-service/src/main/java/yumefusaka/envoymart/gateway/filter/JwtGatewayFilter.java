package yumefusaka.envoymart.gateway.filter;

import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import yumefusaka.envoymart.common.properties.JwtProperties;
import yumefusaka.envoymart.common.util.JwtUtils;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;

@Slf4j
@Component
public class JwtGatewayFilter implements GlobalFilter, Ordered {

    private final JwtProperties jwtProperties;

    public JwtGatewayFilter(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    /**
     * 公开端点：<b>方法 + 路径</b>成对声明。
     * <p>
     * 为什么必须带方法：商品目录是公开读的，但同一前缀下有写接口。
     * 只按路径前缀放行会让「读公开」顺带把「写」一起放出去。
     */
    private static final List<PublicRule> PUBLIC_RULES = List.of(
            PublicRule.of("POST", "/auth/login"),
            PublicRule.of("GET", "/products"),
            PublicRule.of("GET", "/products/**"),
            PublicRule.of("POST", "/payments/callback")
    );

    /**
     * 公开前缀下必须保护的内部路径。
     * <p>
     * 白名单一旦用前缀表达，就必须配一份反向排除：否则同前缀下后来新增的内部接口
     * 会被静默放行。<b>这里曾经真的漏过</b>——`/products` 前缀把内部库存接口
     * `POST /products/stock/deduct`、`/products/stock/restore` 一起放行了，匿名即可改写全站库存。
     */
    private static final List<String> NEVER_PUBLIC_PREFIXES = List.of("/products/stock/");

    /** 路径模式：以 {@code /**} 结尾表示前缀匹配，否则精确匹配。 */
    private record PublicRule(String method, String path) {

        static PublicRule of(String method, String path) {
            return new PublicRule(method, path);
        }

        boolean matches(String requestMethod, String requestPath) {
            if (!method.equalsIgnoreCase(requestMethod)) {
                return false;
            }
            return path.endsWith("/**")
                    ? requestPath.startsWith(path.substring(0, path.length() - 3))
                    : path.equals(requestPath);
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();

        boolean isPublic = NEVER_PUBLIC_PREFIXES.stream().noneMatch(path::startsWith)
                && PUBLIC_RULES.stream().anyMatch(rule -> rule.matches(method, path));
        if (isPublic) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (!StringUtils.hasText(token)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        try {
            Claims claims = JwtUtils.parseToken(jwtProperties.getSecretKey(), token);
            ServerHttpRequest request = exchange.getRequest().mutate()
                    .header(IdentityHeaderInterceptor.USER_ID_HEADER, String.valueOf(claims.get("id")))
                    .build();
            return chain.filter(exchange.mutate().request(request).build());
        } catch (Exception exception) {
            log.warn("Token parse failed: {}", exception.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
