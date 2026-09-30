package yumefusaka.envoymart.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求体体积上限，一个数管住所有服务。
 * <p>
 * <b>不做会怎样</b>：容器会把请求体整段读进堆，再交给 Jackson 解析，解析出来的字符串往往还要
 * 被再处理一次（详情正文要过 jsoup 净化、要拼成 SQL 参数）。一个普通登录用户 POST 一个几百 MB
 * 的报文体，这条链上内存放大好几倍，几条并发就足够让单个服务 OOM——而攻击成本只是一个账号。
 * <p>
 * <b>为什么在 Filter 里按 Content-Length 判断，而不是去调 Jackson 的限制</b>：
 * 试过了，结论是不行。Jackson 3 的 {@code StreamReadConstraints} 有一处静态默认值
 * {@code overrideDefaultStreamReadConstraints}，在容器后处理器里覆盖它、并确认覆盖发生在
 * Tomcat 启动之前（日志时间可查），一个 60 万字符的字段照样走到 Bean Validation 才被拦——
 * 说明 HTTP 报文转换器用的那条 {@code JsonFactory} 链路压根不读这个默认值。
 * Spring Boot 的 {@code spring.jackson.factory.constraints.read.max-string-length} 同样无效，
 * 把它写成 10 也拦不住十万字符的请求。与其跟库的内部结构较劲，不如在**更早、更确定**的位置拦：
 * Filter 在报文进入解析器之前就拿到 Content-Length，超限直接拒绝，连读都不读。
 * <p>
 * <b>覆盖不到的情况，说清楚</b>：分块传输（chunked）的请求没有 Content-Length，这里放行，
 * 由容器自身的连接上限兜底，不在本类的职责内。真实部署里这一层通常还有反向代理
 * （nginx {@code client_max_body_size}）做第一道，本类是应用侧的第二道。
 * <p>
 * <b>为什么返回 HTTP 200 + 业务码 413</b>：全项目约定业务码在响应体、HTTP 恒 200
 * （例外只有 {@code AdminGuardInterceptor} 的 401/403 和网关的内部接口 404）。
 * 这里跟着约定走，前端才能把 msg 直接显示出来，而不是落到一句通用的「请求失败」。
 * <p>
 * 上限取 8MB：本项目没有任何文件上传接口（图片字段存的是 URL 而非字节），最大的合法报文是
 * 带详情正文的商品提交，实测不到 1MB，8MB 留了足够余量。
 * <p>
 * {@code @ConditionalOnWebApplication(SERVLET)} 不能省：网关是纯 WebFlux、classpath 上没有
 * servlet API，而这个类被组件扫描拾取时如果去加载 {@code OncePerRequestFilter} 会直接让网关起不来。
 * {@code GlobalExceptionHandler} 上加同一条件就是为了这件事。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final long MAX_BODY_BYTES = 8L * 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            log.warn("[RequestBody] 拒绝超限请求: {} {} Content-Length={} 上限={}",
                    request.getMethod(), request.getRequestURI(), declared, MAX_BODY_BYTES);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":413,\"data\":null,\"msg\":\"请求体过大（上限 8MB）\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
