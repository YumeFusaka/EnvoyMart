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

    private static final List<String> PUBLIC_PATHS = List.of(
            "/auth/login", "/auth/register",
            "/products",
            "/payments/callback"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean isPublic = PUBLIC_PATHS.stream().anyMatch(path::startsWith);
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
