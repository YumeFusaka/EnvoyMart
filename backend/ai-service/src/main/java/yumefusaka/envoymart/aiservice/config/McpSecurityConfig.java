package yumefusaka.envoymart.aiservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import yumefusaka.envoymart.aiservice.security.McpAuthFilter;

/**
 * 只给 MCP 端点挂鉴权，不影响其他接口。
 */
@Configuration
public class McpSecurityConfig {

    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilter(
            @Value("${jwt.secret-key}") String jwtSecret,
            @Value("${envoymart.mcp.api-key:}") String apiKey) {

        FilterRegistrationBean<McpAuthFilter> registration =
                new FilterRegistrationBean<>(new McpAuthFilter(jwtSecret, apiKey));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setName("mcpAuthFilter");
        registration.setOrder(1);
        return registration;
    }
}
