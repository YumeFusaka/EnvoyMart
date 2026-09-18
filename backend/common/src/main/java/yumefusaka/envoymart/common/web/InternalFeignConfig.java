package yumefusaka.envoymart.common.web;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务间调用的凭证注入 —— 让 Feign 出站请求带上 {@code X-Internal-Token}。
 * <p>
 * <b>它补的是哪一段</b>：{@link InternalCallFilter} 的判定是「请求声称了身份，就得拿得出凭证」。
 * 服务间调用会把用户身份放进 {@code X-User-Id} 头——那正是在声称身份；但
 * {@code X-Internal-Token} 此前<b>只有网关注入</b>，Feign 出站不会自动带上它。
 * 于是这类调用在下游被拦成 401：实测 AI 助手查订单报
 * {@code [401] ... 缺少服务间调用凭证}，而同一服务里不带身份的调用（查商品）正常——
 * 恰好印证了那条判定规则本身没写错，是调用方少带了凭证。
 * <p>
 * <b>为什么修在 common</b>：带身份的 Feign 调用有三处——ai-service 的 OrderClient、
 * payment-service 与 review-service 的 OrderClient。修在它们共同经过的这一层，
 * 一次覆盖全部；分散到各自服务里就是同一个拦截器抄三份。
 * <p>
 * 条件装配让它只在真正使用 Feign 的服务里生效（网关是 WebFlux，不带 Feign）。
 */
@Configuration
@ConditionalOnClass(RequestInterceptor.class)
public class InternalFeignConfig {

    /**
     * 令牌缺失或过短时 {@link InternalAuth#requireValid} 会让服务拒绝启动，
     * 与 {@link InternalCallFilter} 一致——写在仓库里的默认凭证等于没有防护。
     */
    @Bean
    public RequestInterceptor internalTokenInterceptor(@Value("${INTERNAL_TOKEN:}") String internalToken) {
        String token = InternalAuth.requireValid(internalToken);
        return template -> template.header(InternalAuth.TOKEN_HEADER, token);
    }
}
