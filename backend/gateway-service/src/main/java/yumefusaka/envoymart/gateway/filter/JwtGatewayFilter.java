package yumefusaka.envoymart.gateway.filter;

import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
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
import yumefusaka.envoymart.gateway.auth.AuthStateReader;
import yumefusaka.envoymart.common.properties.JwtProperties;
import yumefusaka.envoymart.common.util.JwtUtils;
import yumefusaka.envoymart.common.web.AuthState;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.common.web.InternalAuth;

@Slf4j
@Component
public class JwtGatewayFilter implements GlobalFilter, Ordered {

    private final JwtProperties jwtProperties;
    private final String internalToken;
    private final AuthStateReader authStateReader;

    public JwtGatewayFilter(JwtProperties jwtProperties,
                            @Value("${INTERNAL_TOKEN:}") String internalToken,
                            AuthStateReader authStateReader) {
        this.jwtProperties = jwtProperties;
        // 网关是注入方：拿不到令牌就无法把身份可信地传给下游，同样拒绝启动
        this.internalToken = InternalAuth.requireValid(internalToken);
        this.authStateReader = authStateReader;
    }

    /**
     * 公开端点：<b>方法 + 路径</b>成对声明。
     * <p>
     * 为什么必须带方法：商品目录是公开读的，但同一前缀下有写接口。
     * 只按路径前缀放行会让「读公开」顺带把「写」一起放出去。
     */
    private static final List<PublicRule> PUBLIC_RULES = List.of(
            PublicRule.of("POST", "/auth/login"),
            PublicRule.of("POST", "/auth/register"),
            PublicRule.of("GET", "/products"),
            PublicRule.of("GET", "/products/**"),
            // 类目树与品牌：商品目录的一部分，游客不登录也要能看见分类导航和品牌筛选，
            // 否则商品页公开读、导航却要登录，是自相矛盾的
            PublicRule.of("GET", "/categories/**"),
            PublicRule.of("GET", "/brands/**"),
            // 知识库只读：引用要让**任何人**都能自己核对，「登录了才给你看依据」
            // 与溯源的目的正好相反。这里也没有任何用户数据，全是平台规则与说明书
            // 商品评价：商品详情公开，而评价是详情的一部分 ——
            // 「详情能看、大家怎么说要登录才给看」是自相矛盾的，而且评价里没有任何
            // 用户数据（提交者已按匿名/昵称脱敏）。同前缀下的写接口是 POST，不受影响；
            // /reviews/admin/** 由 ADMIN_SEGMENT 强制登录
            PublicRule.of("GET", "/reviews/spu/**"),
            PublicRule.of("GET", "/knowledge/documents"),
            PublicRule.of("GET", "/knowledge/documents/**"),
            PublicRule.of("GET", "/knowledge/chunks/**"),
            // 图谱同上：它返回的每一条边都带着知识库文档里的原文引文，
            // 公开它和公开那些文档是同一件事。真实用户数据一条都没有
            PublicRule.of("GET", "/knowledge/graph/**"),
            // 检索评测报告：与知识库文档同理——「检索质量 0.633」是个对外的质量声明，
            // 让任何人打开报告、点重新运行就能现场复现，是它可信的全部理由；
            // 只给登录用户看没有意义，报告里也没有任何用户数据。
            // 只放行「读报告」这一个端点：同前缀下的重跑接口在 /admin 段下，由 ADMIN_SEGMENT 强制登录
            // 回答质量报告：与检索评测同一条理由——幻觉率 / 引用准确率是对外的质量声明，
            // 让任何人打开就能看到判定链的重放结果与来源标注（夹具采集时间、模型），
            // 是这组数字可信的全部理由；报告里没有一条用户数据。
            // 真跑要花模型配额，在 /ai/admin/ 段下，由 ADMIN_SEGMENT 强制登录
            PublicRule.of("GET", "/ai/eval/grounding/report"),
            // 生产链路检索报告：同为对外的质量声明。它展示的是「用户此刻在用的那条链路」
            // （真实向量 + 图谱 + 重排 + 扩写）跑在生产语料上的成绩，报告里没有用户数据。
            // 真跑要花 embedding / 重排配额，在 /ai/admin/ 段下，由 ADMIN_SEGMENT 强制登录
            PublicRule.of("GET", "/ai/eval/retrieval/report"),
            // 上传的商品图片：与商品本身同为公开读。
            // <img> 标签不会带 Authorization 头，要求登录只会让图片全部加载失败，
            // 而图片里没有任何用户数据。上传接口在 /products/admin/media 下，由
            // ADMIN_SEGMENT 强制登录 + 管理员角色，不受这条放行影响
            PublicRule.of("GET", "/media/**"),
            PublicRule.of("POST", "/payments/callback")
    );

    /**
     * 路径里的完整 {@code admin} 段 —— 命中即不可能是公开资源。
     * <p>
     * 按「段」匹配而不是 {@code contains("admin")}：后者会把
     * {@code /knowledge/documents/admin-guide} 这类**文档编号里带 admin 的公开资源**一起禁掉。
     * 用 {@code /admin/} 或结尾 {@code /admin} 才是段级判定。
     */
    private static final Pattern ADMIN_SEGMENT = Pattern.compile("/admin(?:/|$)");

    /**
     * 只允许服务间调用、绝不经网关暴露的路径。
     * <p>
     * 名单里的是各服务 {@code /internal/} 下的接口：order-service 用 Feign <b>直连</b>
     * product-service 扣库存，ai-service 直连 knowledge-service 写图谱，都是这条通道。
     * 它们<b>不表达「谁在操作」</b>——没有调用方身份，只有「服务间」这一个属性。
     * 而网关原先只判「是否登录」、不做授权，于是任何能登录的用户带自己的 Token 调它们都会被放行：
     * 实测普通账号 {@code alice} 可以把任意商品库存扣到 0（等于拒绝销售），
     * 或用 restore 无限灌库存（配合下单链路直接超卖）。
     * <p>
     * <b>它们不是「管理员接口」</b>，所以不该用角色来放行——一个 ADMIN 用户同样不该直接调
     * 扣库存。管理台要用的能力得另开一条走 {@code @RequireAdmin} 的用户侧路径
     * （见 ai-service 的 {@code /ai/admin/knowledge}），而不是把这些服务间接口开个口子。
     * <p>
     * 对外一律按「资源不存在」处理（404），不暴露这些路径的存在；服务间调用走 Feign
     * 直连端口，不受影响。
     * <p>
     * 这一条同时也是「公开前缀必须配一份反向排除」的那份排除：{@code /products/**}
     * 是公开读的，若只做前缀放行，同前缀下后来新增的内部接口会被静默放行——
     * 这个坑真的踩过。
     * <p>
     * <b>漏登记是这份清单的默认失败模式</b>，而且已经真的发生过一次：{@code /orders/internal/}
     * 长期不在清单里，发货接口唯一的防线（「网关已屏蔽」）于是根本不存在。<b>靠人记不可靠</b>，
     * 所以 {@code InternalEndpointCoverageTest} 会扫描全部控制器源码，
     * 新增内部接口却忘了登记就直接构建失败。包级可见是为了让那个测试读到它。
     * <p>
     * 反过来，<b>清单里也不留已经搬走的路径</b>：原先的 {@code /ai/internal/knowledge}
     * （重建检索索引）挪到了 {@code /ai/admin/knowledge}，这条就跟着删了。
     * 留着一条护不住任何东西的记录，会让人以为那条路还在被守着。
     */
    static final List<String> INTERNAL_ONLY_PREFIXES = List.of(
            "/products/stock/",
            // 全量商品目录，给 ai-service 建知识图谱做实体链接用。数据本身不敏感，
            // 但它是「不分页拉全库」的口子——公开出去等于给了每个匿名请求一条
            // 绕过 /products 分页上限的取数通道
            "/products/internal/",
            // 注意 /orders/internal/ 与 /after-sales/internal/ 两项**已经删掉**，
            // 因为那段路径下的接口全搬走了：
            //   发货  → /orders/admin/orders/{id}/ship
            //   售后审核 / 确认收货 / 重试退款 → /after-sales/admin/after-sales/{id}/...
            // 它们原先在这里是因为「服务间通道，网关一律 404」——那既是它们唯一的一道门，
            // 也是它们不记录操作人的理由。管理台需要身份与追责，所以改成了走网关的管理接口。
            //
            // 留着一条护不住任何东西的记录会让人以为那条路还在被守着。清单与源码的一致性
            // 由 InternalEndpointCoverageTest 双向兜住：源码里有、清单里没有会失败；
            // 而清单里多出来的项没有自动检查，所以搬走时**必须手工删**——就是这里这一步。
            //
            // 服务间退款入口：它没有调用方身份，只表达「这笔订单的钱要还回去」，
            // 经网关暴露出去等于任何人凭订单号就能触发退款
            "/payments/internal/",
            // 优惠券核销：让用户能自己核销等于让他自己改优惠金额
            "/coupons/internal/",
            // 用户收货历史：评价侧判「新账号刷评」用。它读的是别人的交易时间线，
            // 公开出去等于给每个匿名请求一个「这个账号什么时候第一次收货」的探测口
            "/orders/internal/",
            // 知识库内部接口：语料下发与种子重导。上面刚把 /knowledge/** 开了公开读，
            // 这一条就是那份必须存在的反向排除——不做的话，同前缀下的内部接口会被静默放行
            "/knowledge/internal/",
            // 评价聚合重建：重算全量商品评分并触发下游重算与索引重建。
            // 它本身幂等、不写用户数据，但它是「我一个请求让整条派生链路全量跑一遍」的
            // 放大器——公开出去等于给每个匿名请求一个打满 product-service 与 ES 的开关。
            // 运维/演示从 review-service 端口直连调用（run-local.sh demo 就是这么做的）
            "/reviews/internal/",
            // AI 服务的索引重建内部通道：商品上下架联动时由 product-service 调用。
            // 管理台那条重建接口在 /ai/admin/ 下（要人来点、验身份）；这一条没有调用方身份，
            // 靠够不着建立信任。公开出去等于给任何匿名请求一个「清空并重算索引」的开关
            "/ai/internal/");

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

        // 入站的身份头必须先剥掉，再决定放不放行。
        //
        // 此前网关只做注入、不做剥离，等于把「客户端自己塞的身份头」放行进下游：公开路径
        // （登录、商品目录、支付回调）连注入都没有，伪造的 X-User-Id 会<b>原样透传</b>；
        // 认证路径则依赖 HttpHeaders.put 的覆盖语义才没被伪造值顶掉——防护挂在「碰巧被覆盖」上，
        // 而不是挂在「客户端的东西根本到不了下游」上。下游确实还有 InternalCallFilter 兜底
        // （带了身份头却拿不出服务间令牌的请求拒绝），但那是每个服务都要单独装上的第二道防线，
        // 不适合作为唯一依赖。剥离之后，下游看到的身份头要么是网关注入的，要么根本不存在。
        //
        // X-Internal-Token 一并剥掉：客户端的值不该有机会与网关注入的混在一起。
        ServerHttpRequest.Builder downstreamRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(IdentityHeaderInterceptor.USER_ID_HEADER);
                    headers.remove(IdentityHeaderInterceptor.USER_ROLE_HEADER);
                    headers.remove(InternalAuth.TOKEN_HEADER);
                });

        // 管理接口不是公开资源，一律先要求登录——**这条规则比白名单本身优先级高**。
        //
        // 起因：白名单里有 `GET /products/**`、`GET /categories/**`，而管理接口就挂在这些前缀下
        // （`/products/admin/spus`、`/categories/admin`）。前缀放行意味着它们会被判成公开，
        // 请求会**不带任何身份**地进到服务里——只剩 `AdminGuardInterceptor` 一道防线。
        // 那道防线确实拦得住（无 X-User-Id 即 401），但这是"只剩最后一道"，
        // 而不是"本来就进不来"。写白名单的人加一条 `GET /products/**` 时不会想到
        // 它同时把管理接口也放开了；只要有人**不读源码**就发现不了。
        //
        // 用「路径段里有 admin」这个通用判据，而不是给每个管理前缀补一条排除：
        // 后者要为 product / order / after-sale / review / auth / tickets 各写一遍，
        // 而且新增一个服务就多一处会漏。判据一次写对，之后所有 `/xxx/admin/...` 自动生效。
        boolean isAdminPath = ADMIN_SEGMENT.matcher(path).find();
        boolean isPublic = !isAdminPath && PUBLIC_RULES.stream().anyMatch(rule -> rule.matches(method, path));
        if (isPublic) {
            return chain.filter(exchange.mutate().request(downstreamRequest.build()).build());
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
            String userId = String.valueOf(claims.get("id"));
            // 角色取自签了名的 Token（claim 的写入方是 auth-service，取值来自 sys_user.role_name）。
            // 角色是这个批次新加的 claim，此前签发的 Token 里没有它——那种 Token 不注入角色头，
            // 下游解析不出 ADMIN 会按 403 拒绝，是安全的方向；绝不能退化成「没有角色就当普通用户放行」。
            Object tokenRole = claims.get(JwtUtils.CLAIM_ROLE);

            // 最后一道问询：这个 Token 现在还作数吗？
            //
            // JWT 是自证的，签发之后服务端就再也管不着它了——于是「禁用用户」与「改角色」
            // 这两个管理动作只改数据库的话，对方手里的 Token 还能继续用到过期（本项目 7 天）。
            // 那等于没禁用。所以每次鉴权都去 Redis 问一次，那里的记录才是当前真相。
            //
            // 代价是每个认证请求多一次 Redis 往返。这是「Token 可撤销」的固有成本，
            // 换来的是管理动作能立即生效——对一个要演示「禁用后立刻踢下线」的系统，
            // 这笔账是划算的。真嫌贵可以上布隆过滤器 + 本地缓存，那是后话。
            return authStateReader.read(userId)
                    // 兜底：接口约定「读不到就返回空」，但那是对实现的约定，不是编译器能保证的事。
                    // 一旦哪个实现把异常放出来，这里就是全站认证请求 500 —— 而它想要的语义
                    // 明明是「读不到就按旧行为放行」。所以在这唯一的消费点再兜一层
                    .onErrorResume(e -> Mono.empty())
                    // 用 defaultIfEmpty 而不是让空 Mono 直接穿过 flatMap：没有记录（绝大多数用户）
                    // 是常态，空流会在 flatMap 处直接完成，请求<b>既不放行也不报错</b>地挂在那里
                    .defaultIfEmpty("")
                    .flatMap(state -> {
                        if (AuthState.isDisabled(state)) {
                            log.warn("已禁用账号的请求被拒: userId={}, path={}", userId, path);
                            return reject(HttpStatus.UNAUTHORIZED, "账号已被禁用");
                        }
                        // 身份头与「这是我加的」的凭证一起注入。下游两个都要看：只有身份头说明不了
                        // 它是网关加的，还是调用方自己塞的；而下游服务的端口是直接监听的，直连就能绕过网关。
                        // 两者绑在一起，「这个身份声明可信」才有依据。
                        downstreamRequest
                                .header(IdentityHeaderInterceptor.USER_ID_HEADER, userId)
                                .header(InternalAuth.TOKEN_HEADER, internalToken);
                        // 角色以 Redis 为准：Token 里那个是签发时刻的快照，提权 / 降权之后就是过期的。
                        // 只有 Redis 里没有记录时才退回 Token 里的角色（「只存异常用户」的直接结果）
                        String role = AuthState.roleOf(state);
                        if (role == null && tokenRole != null) {
                            role = String.valueOf(tokenRole);
                        }
                        if (role != null) {
                            downstreamRequest.header(IdentityHeaderInterceptor.USER_ROLE_HEADER, role);
                        }
                        return chain.filter(exchange.mutate().request(downstreamRequest.build()).build());
                    });
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
     * <p>
     * <b>段内 {@code ;} 之后的内容要一起截掉</b>，理由同上：Servlet 规范把 {@code ;} 起头的那一段
     * 当作路径参数（原为 {@code ;jsessionid=}），容器<b>不把它算进路由路径</b>，而网关这一侧
     * 若原样保留，两边看到的路径就不是同一个。实测过绕过：匿名请求
     * {@code GET /products/internal;x/catalog} 经网关时 {@code startsWith("/products/internal/")}
     * 因分号处字符不匹配而落空，请求进了「需登录」分支；下游却把 {@code ;x} 剥掉照常路由到
     * 内部方法——白名单的每一项都有一个 {@code ;x} 变体，整份排除清单同时失效。
     * <p>
     * 截断位置在解码<b>之后</b>，{%3B} 写进来的分号同样会被截掉。方向是安全的：
     * 万一下游不剥，网关也只会把请求判成内部路径而回 404，不会把内部路径放行。
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
        // 折叠重复斜杠、消掉 "." 与 ".." 段、截掉路径参数，得到与下游容器一致的形式
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : decoded.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                segments.pollLast();
                continue;
            }
            int param = segment.indexOf(';');
            String name = param < 0 ? segment : segment.substring(0, param);
            // 截完变空（整段就是个 ;x）时丢掉，别留下双斜杠——下游容器同样会折叠掉
            if (!name.isEmpty()) {
                segments.addLast(name);
            }
        }
        return "/" + String.join("/", segments);
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
