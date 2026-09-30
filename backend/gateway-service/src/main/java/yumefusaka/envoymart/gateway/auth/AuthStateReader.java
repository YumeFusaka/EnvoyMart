package yumefusaka.envoymart.gateway.auth;

import reactor.core.publisher.Mono;

/**
 * 读取某个用户的当前认证态（{@code "0|USER"} 这种），没有记录时给空。
 * <p>
 * 抽出这一个方法而不是让过滤器直接拿 {@code ReactiveStringRedisTemplate}，是为了让
 * {@code JwtGatewayFilter} 保持「纯判定、无外部依赖」——它的测试里塞一个
 * {@code userId -> Mono.empty()} 就能覆盖全部鉴权分支，不必去 mock 一个 Redis 模板，
 * 也不必为了跑单测而真的起一个 Redis。
 * <p>
 * <b>实现必须自己吞掉故障并返回空</b>，不能让异常冒到过滤器：读取侧失败要退化成
 * 「按 Token 里的角色放行」，而不是「全站请求 500」。
 */
public interface AuthStateReader {

    /** @return 认证态字符串；没有记录或读不到时为空 Mono */
    Mono<String> read(String userId);
}
