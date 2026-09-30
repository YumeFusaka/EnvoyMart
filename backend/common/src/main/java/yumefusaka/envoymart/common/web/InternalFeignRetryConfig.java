package yumefusaka.envoymart.common.web;

import feign.Request;
import feign.RequestInterceptor;
import feign.Response;
import feign.RetryableException;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 服务间调用的瞬时失败重试 —— 补的是「下游抖一下，链路上有人的工具体验就断一次」这一档。
 * <p>
 * <b>为什么要有重试</b>：{@code ToolRegistry} 里每个工具对 Feign 异常都是 catch 后转成一句
 * errorMessage——这是对的（错误要变成模型能读的话），但它让「下游重启中的一次拒连」
 * 与「下游真的坏了」在 Agent 眼里长得一样，都变成"工具执行失败"。前者重试一次就好，
 * 后者重试也没用。区分这两者属于调用层，工具层看不见也管不了。
 * <p>
 * <b>为什么敢重试，以及为什么只重试这些</b>：重试写请求是有代价的——响应丢了不代表业务没生效。
 * 所以判据只收三类<b>可证明安全</b>的失败（见 {@link TransientRetryer#safeToRetry}）：
 * 连接从未建立（请求肯定没送达）、5xx 中的「明确未处理」状态、以及幂等 GET 的超时。
 * 4xx 一律不重试——参数错、无权限，重试一百次也是同样的错。
 * <p>
 * <b>为什么修在 common</b>：与 {@link InternalFeignConfig} 同一位置、同一理由——
 * Feign 客户端散在 ai-service / order-service / payment-service / review-service 四处，
 * 修在它们共同经过的这一层一次覆盖全部。条件装配让它只在真正使用 Feign 的服务里生效。
 * <p>
 * <b>bean 定义在主上下文为什么能生效</b>：Spring Cloud OpenFeign 为每个客户端建子上下文，
 * 子上下文的 parent 就是主上下文，而 {@code FeignClientsConfiguration} 的默认
 * Retryer / ErrorDecoder 都带 {@code @ConditionalOnMissingBean}（默认跨祖先搜索）——
 * 这里的 bean 会让那些默认值整体退让。这也是官方文档"想改所有客户端的默认值，
 * 就把 bean 放在主上下文"的用法。
 */
@Configuration
@ConditionalOnClass(RequestInterceptor.class)
public class InternalFeignRetryConfig {

    @Bean
    public ErrorDecoder transientErrorDecoder() {
        return new TransientErrorDecoder();
    }

    @Bean
    public Retryer transientRetryer() {
        return new TransientRetryer();
    }

    /**
     * 把「明确未处理」的下游失败翻译成可重试异常，其余保持原有语义。
     * <p>
     * 先委托默认实现，再由本类决定是否包装——4xx 的业务语义（404/409/401 映射成什么异常）
     * 完全不变，只多一层「这个失败值不值得再试一次」的判定。
     */
    static final class TransientErrorDecoder implements ErrorDecoder {

        private final ErrorDecoder delegate = new ErrorDecoder.Default();

        @Override
        public Exception decode(String methodKey, Response response) {
            Exception decoded = delegate.decode(methodKey, response);
            if (!worthRetrying(response)) {
                return decoded;
            }
            return new RetryableException(response.status(), decoded.getMessage(),
                    response.request().httpMethod(), decoded, (Long) null, response.request());
        }

        private static boolean worthRetrying(Response response) {
            int status = response.status();
            boolean idempotent = response.request().httpMethod() == Request.HttpMethod.GET;
            // 503 是服务端明确说「暂时不可用」（优雅停机、过载拒绝）——业务没被处理，什么方法都可重试。
            // 502/504 是网关类失败：下游可能处理完了、只是响应丢在回来的路上，
            // 写请求重试就有重复副作用，所以只放行幂等的 GET。
            // 500 刻意不在列：那是业务代码执行到一半崩了，状态未知，重试是在赌。
            return status == 503 || ((status == 502 || status == 504) && idempotent);
        }
    }

    /**
     * 只在「可证明安全」时重试的 Retryer：200ms 基数、1.5 倍退避、500ms 封顶。
     * <p>
     * {@code maxAttempts=3} 是「总共 3 次尝试」的语义（首次 + 2 次重试），退避序列 300ms、450ms——
     * 最坏给单次调用增加约 0.75 秒，不会把工具调用的等待堆到用户可感知的量级。
     */
    @Slf4j
    static final class TransientRetryer extends Retryer.Default {

        TransientRetryer() {
            super(200, 500, 3);
        }

        private int retries;

        @Override
        public void continueOrPropagate(RetryableException e) {
            if (!safeToRetry(e)) {
                throw e;
            }
            // 先交给父类：尝试次数耗尽时它会在这里抛出，那就不该记一条「重试」的日志
            super.continueOrPropagate(e);
            // 只打根因：e.getMessage() 自带「... executing GET <url>」，与前面的 method+url 重复
            Throwable cause = e.getCause();
            log.warn("[Feign重试] {} {} 第 {} 次重试：{}", e.method(), e.request().url(), ++retries,
                    cause == null ? e.getMessage() : cause.toString());
        }

        /**
         * 重试的安全性判据 —— 宁缺毋滥，判不准就不重试。
         * <ul>
         *   <li>域名解析失败 / 连接被拒：请求肯定没送达，任何方法都能重试；</li>
         *   <li>读写超时：请求可能已送达并生效，只重试幂等的 GET；</li>
         *   <li>5xx：{@link TransientErrorDecoder} 已按方法判定过，到这里放行。
         *       429 带 Retry-After 的可重试响应刻意不跟——不遵守 Retry-After 的快速重试
         *       只会加重限流方的负担。</li>
         * </ul>
         */
        private static boolean safeToRetry(RetryableException e) {
            Throwable cause = e.getCause();
            boolean idempotent = e.method() == Request.HttpMethod.GET;
            if (cause instanceof UnknownHostException || cause instanceof ConnectException) {
                return true;
            }
            if (cause instanceof SocketTimeoutException) {
                return idempotent;
            }
            int status = e.status();
            return status == 503 || ((status == 502 || status == 504) && idempotent);
        }

        /**
         * 必须覆写：父类 {@code clone()} 返回的是 {@code new Retryer.Default(...)}——
         * 不覆写的话，每个请求克隆出来的都是没有安全判据的普通重试器，
         * 本类的全部约束会被静默丢掉（Feign 每次调用都 clone 一份，这个路径必然发生）。
         */
        @Override
        public Retryer clone() {
            return new TransientRetryer();
        }
    }
}
