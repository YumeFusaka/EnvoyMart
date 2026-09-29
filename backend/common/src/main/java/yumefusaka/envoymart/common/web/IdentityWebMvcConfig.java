package yumefusaka.envoymart.common.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class IdentityWebMvcConfig implements WebMvcConfigurer {

    @Bean
    public IdentityHeaderInterceptor identityHeaderInterceptor() {
        return new IdentityHeaderInterceptor();
    }

    @Bean
    public AdminGuardInterceptor adminGuardInterceptor() {
        return new AdminGuardInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(identityHeaderInterceptor());
        // 管理接口守卫自己读请求头判定，不依赖上一个拦截器写进 BaseContext 的身份，
        // 因此两者在顺序上无耦合；这里跟着注册只是让「身份 → 授权」的阅读顺序保持自然
        registry.addInterceptor(adminGuardInterceptor());
    }
}
