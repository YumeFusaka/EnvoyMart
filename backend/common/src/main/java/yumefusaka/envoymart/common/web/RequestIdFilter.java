package yumefusaka.envoymart.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把请求标识放进 MDC，然后在响应头里回写同一个值。
 * <p>
 * <b>为什么排在最前</b>：它要覆盖后面所有过滤器的日志——体积超限被拒、身份不足被拒这些
 * 恰恰是最需要线索的路径。<b>只有 {@link RequestSizeLimitFilter} 在它之前</b>，
 * 因为那一条要在读取请求体之前就做判断，晚一步就没有意义了。
 * <p>
 * <b>回写响应头</b>：用户报障时说不出日志里那串 id，但浏览器里能看到它；
 * 前端把它显示在对话详情里，「这一次对话」就能和后台日志对上。
 * <p>
 * <b>为什么必须在 finally 里清理</b>：Tomcat 的请求线程是复用的。不清理的话，
 * 下一个请求——可能是完全不相干的另一个用户——会继承上一个请求的标识，
 * 而它看起来完全正常，只是把两条无关的轨迹错误地缝在一起。这正是"静默失效"的形态，
 * 所以宁可每次多一行 remove。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = RequestId.resolve(request.getHeader(RequestId.HEADER));
        MDC.put(RequestId.MDC_KEY, requestId);
        // 先回写再进链路：被下游拒绝（401/413）时同样要带上，否则恰恰是失败请求查不到 id
        response.setHeader(RequestId.HEADER, requestId);
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            accessLog(request, response, startedAt);
            MDC.remove(RequestId.MDC_KEY);
        }
    }

    /**
     * 入站访问日志。
     * <p>
     * <b>为什么必须有这一行</b>：一次问答跨三个服务，上游日志只证明「我发出去了」，
     * 下游收没收到、用哪个标识处理的，只有下游自己记下来才算数。缺了它，链路的
     * 最后一跳永远只能靠意图推断——而下游服务大多对读接口不打日志（读是无副作用的，
     * 不值得为它写业务日志），于是「调用发生了」这件事在系统里没有任何痕迹。
     * <p>
     * <b>为什么不记在业务代码里</b>：每个服务的每个接口都补一行，等于把同一件事写 N 遍，
     * 且新人加接口时必然漏。这里是所有请求的唯一必经之处，写一次就全覆盖。
     * <p>
     * 健康检查排除在外：探活是每秒级的，记进来会把日志冲成只剩心跳。
     */
    private void accessLog(HttpServletRequest request, HttpServletResponse response, long startedAt) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/actuator")) {
            return;
        }
        log.info("[HTTP] {} {} -> {} {}ms", request.getMethod(), uri, response.getStatus(),
                (System.nanoTime() - startedAt) / 1_000_000);
    }
}
