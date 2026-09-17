package yumefusaka.envoymart.gateway.filter;

import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import yumefusaka.envoymart.common.properties.JwtProperties;
import yumefusaka.envoymart.common.util.JwtUtils;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.common.web.InternalAuth;

@Slf4j
@Component
public class JwtGatewayFilter implements GlobalFilter, Ordered {

    private final JwtProperties jwtProperties;
    private final String internalToken;

    public JwtGatewayFilter(JwtProperties jwtProperties,
                            @Value("${envoymart.internal.token:}") String internalToken) {
        this.jwtProperties = jwtProperties;
        // 网关是注入方：拿不到令牌就无法把身份可信地传给下游，同样拒绝启动
        this.internalToken = InternalAuth.requireValid(internalToken);
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
     * 只允许服务间调用、绝不经网关暴露的路径。
     * <p>
     * 这几个是 order-service 用 Feign <b>直连</b> product-service 的内部接口
     * （扣减/回补库存），不经过网关。但网关原先只判「是否登录」、不做授权，而 JWT 里
     * 也没有角色字段（payload 只有 id/username/exp）——任何能登录的用户都能调它们。
     * 实测普通账号 {@code alice} 可以把任意商品库存扣到 0（等于拒绝销售），
     * 或用 restore 无限灌库存（配合下单链路直接超卖）。
     * <p>
     * 对外一律按「资源不存在」处理（404），不暴露这些路径的存在；服务间调用走 Feign
     * 直连 9002，不受影响。
     * <p>
     * 这一条同时也是「公开前缀必须配一份反向排除」的那份排除：{@code /products/**}
     * 是公开读的，若只做前缀放行，同前缀下后来新增的内部接口会被静默放行——
     * 这个坑真的踩过。
     */
    private static final List<String> INTERNAL_ONLY_PREFIXES = List.of("/products/stock/");

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
        String path = normalise(exchange.getRequest().getPath().value());
        String method = exchange.getRequest().getMethod().name();

        // 内部接口对外按"不存在"处理，放在白名单判断之前——放它进白名单逻辑，
        // 就还得依赖那份反向排除写得对，多一处能写错的地方
        if (INTERNAL_ONLY_PREFIXES.stream().anyMatch(path::startsWith)) {
            return reject(HttpStatus.NOT_FOUND, "资源不存在");
        }

        boolean isPublic = PUBLIC_RULES.stream().anyMatch(rule -> rule.matches(method, path));
        if (isPublic) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (!StringUtils.hasText(token)) {
            return reject(HttpStatus.UNAUTHORIZED, "缺少访问令牌");
        }
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        try {
            Claims claims = JwtUtils.parseToken(jwtProperties.getSecretKey(), token);
            // 身份头与「这是我加的」的凭证一起注入。下游两个都要看：只有身份头说明不了
            // 它是网关加的，还是调用方自己塞的；而下游服务的端口是直接监听的，直连就能绕过网关。
            // 两者绑在一起，「这个身份声明可信」才有依据。
            ServerHttpRequest request = exchange.getRequest().mutate()
                    .header(IdentityHeaderInterceptor.USER_ID_HEADER, String.valueOf(claims.get("id")))
                    .header(InternalAuth.TOKEN_HEADER, internalToken)
                    .build();
            return chain.filter(exchange.mutate().request(request).build());
        } catch (Exception exception) {
            log.warn("Token parse failed: {}", exception.getMessage());
            return reject(HttpStatus.UNAUTHORIZED, "令牌无效或已过期");
        }
    }

    /**
     * 拒绝请求 —— <b>抛异常而不是直接写响应</b>。
     * <p>
     * 直接 {@code setStatusCode(...); setComplete()} 看起来更省事，但会踩一个不显眼的时序：
     * Gateway 的过滤器链是<b>同步递归调用</b>的，{@code chain.filter()} 会立即执行后续过滤器的
     * 方法体。Sentinel 的过滤器顺序在最前（{@code HIGHEST_PRECEDENCE}），它同样是先调用
     * {@code chain.filter(exchange)} 再做限流判定——于是鉴权分支会在限流判定<em>之前</em>
     * 就把响应提交掉。
     * <p>
     * 后果实测：无 token 且请求量超过路由阈值时，Sentinel 判定超限后想写的 429 永远写不进去
     * （{@code committed=true, status=401}），客户端拿到的是 <b>HTTP 200 + 空 body</b>；
     * 而带 token 的同类请求因为不会提前提交，能正常收到 429。同一个限流规则，两种客户端
     * 看到两种结果。
     * <p>
     * 抛异常则没有这个问题：返回的是惰性的 error Mono，谁都没提前碰响应，
     * 最终由 {@code GatewayErrorHandler} 统一出口写成 401/429。
     */
    private Mono<Void> reject(HttpStatus status, String reason) {
        return Mono.error(new ResponseStatusException(status, reason));
    }

    /**
     * 路径规范化后再做白名单判定。
     * <p>
     * {@code getPath().value()} 是<b>未解码</b>的原始路径，而下游 Tomcat 会解码并归一化。
     * 两者不一致时白名单就会给出错误结论：实测 {@code /products/./stock/deduct} 绕过了
     * {@code /products/stock/} 反向排除（{@code /products/**} 命中公开规则，前缀排除却没命中），
     * 匿名请求被放行到下游；{@code /products/%73tock/deduct} 同理。
     * <p>
     * 当前还不构成可利用的越权——写接口只接受 POST，而白名单只放了 GET——但这是纵深防御的
     * 缺口：一旦白名单新增任何 GET 的敏感接口，或者前面多一层会归一化路径的反向代理，
     * 它就立刻变成匿名越权。判定用的路径必须和下游看到的路径是同一个。
     */
    private String normalise(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 解不开的百分号序列：原样使用，让它匹配不上白名单（fail-closed）
            return rawPath;
        }
        // 折叠重复斜杠、消掉 "." 与 ".." 段，得到与下游容器一致的形式
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : decoded.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                segments.pollLast();
                continue;
            }
            segments.addLast(segment);
        }
        return "/" + String.join("/", segments);
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
