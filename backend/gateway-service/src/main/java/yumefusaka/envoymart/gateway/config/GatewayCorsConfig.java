package yumefusaka.envoymart.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 网关 CORS 配置 —— 让浏览器里的前端能跨域调用本网关。
 * <p>
 * 开发态前端跑在 5173、网关在 8080，属于跨域。没有这段配置时，预检请求会返回 200 但
 * <b>不带任何 {@code Access-Control-Allow-*} 头</b>，浏览器随即拦掉真实请求——
 * 表现是前端每个接口都失败，而网关日志里看不到任何错误（请求根本没发出去）。
 * <p>
 * 之所以用 {@code CorsWebFilter} 而不是 yml 里的 {@code globalcors}：后者在
 * Spring Cloud Gateway 5.x 上换了配置前缀，写错了不会报错、只会静默失效——
 * 而"静默失效"正是这次要避免的失败模式。
 * <p>
 * 允许的来源通过 {@code envoymart.cors.allowed-origins} 配置，默认只放开发前端。
 * <b>生产必须显式收紧</b>，不要把它当成通配。
 */
@Configuration
public class GatewayCorsConfig {

    private final List<String> allowedOrigins;

    public GatewayCorsConfig(
            @Value("${envoymart.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
            String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 用 OriginPatterns 而非 Origins：前者允许与 allowCredentials 组合
        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        // 前端会带 Authorization（JWT）与 Content-Type，预检必须放行这两个
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
