package yumefusaka.envoymart.gateway.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import yumefusaka.envoymart.common.web.AuthState;

/**
 * 从 Redis 读认证态。键名与格式由 {@link AuthState} 统一，auth-service 是写入方。
 * <p>
 * <b>读失败一律降级成「没有记录」</b>：Redis 挂了不该让整个站点登不上。
 * 降级的代价是「已禁用用户手里的旧 Token 在这段时间内被放行」——它比「全站 401」
 * 轻得多，而且该场景下 auth-service 的登录链路本来就查库，禁用用户登不进来、
 * 也拿不到新 Token，受影响窗口仅限于他手里那张旧票。
 * <p>
 * 反过来，<b>写入侧不能降级</b>（见 auth-service 的 {@code AuthStateStore}）：
 * 那边失败意味着「管理员以为禁用生效了、实际没有」，是一条没人知道的口子。
 * 读松写紧，两侧的不对称是有意的。
 * <p>
 * 用响应式模板而不是阻塞式：网关跑在 Netty 上，过滤器链里任何一个 block 都会占住
 * 事件循环线程，把「非阻塞」这件事作废。连接超时在 application.yml 里压到 1 秒——
 * 默认的 60 秒会让「降级」变成「慢速失败」，每个请求白等一分钟才算作数。
 */
@Slf4j
@Component
public class RedisAuthStateReader implements AuthStateReader {

    private final ReactiveStringRedisTemplate redisTemplate;

    public RedisAuthStateReader(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<String> read(String userId) {
        if (userId == null || userId.isBlank() || "null".equals(userId)) {
            // Token 里没有 id 时没必要去查，也查不出东西
            return Mono.empty();
        }
        return redisTemplate.opsForHash().get(AuthState.KEY, userId)
                // ReactiveHashOperations 的取值签名是 Object（哈希的 field/value 都是 Object），
                // 这里存的本来就是字符串，转回来即可
                .map(String::valueOf)
                .onErrorResume(e -> {
                    log.warn("[AuthState] 读取失败，本次按「无记录」放行 userId={}: {}", userId, e.getMessage());
                    return Mono.empty();
                });
    }
}
