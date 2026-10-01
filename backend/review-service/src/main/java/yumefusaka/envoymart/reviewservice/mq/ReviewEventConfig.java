package yumefusaka.envoymart.reviewservice.mq;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评价领域的事件交换机。
 * <p>
 * <b>这里只声明交换机，不声明队列。</b>队列与绑定属于消费方（product-service）——
 * 发事件的模块不该决定下游怎么消费、要不要重试、失败了进哪个死信队列。
 * 交换机的声明是幂等的（同名同类型同持久化），两边各声明一次不会冲突。
 * <p>
 * 命名沿用项目既有约定：{@code envoymart.order}（order-service 的事件）、
 * {@code envoymart.cache}（product-service 的缓存失效），这里是 {@code envoymart.review}。
 */
@Configuration
public class ReviewEventConfig {

    public static final String REVIEW_EXCHANGE = "envoymart.review";

    /** 商品的评分聚合发生了变化（新增评价、隐藏、恢复） */
    public static final String AGGREGATE_CHANGED_KEY = "review.aggregate.changed";

    @Bean
    public TopicExchange reviewExchange() {
        return new TopicExchange(REVIEW_EXCHANGE, true, false);
    }

    /**
     * 事件对象用 JSON 序列化。
     * <p>
     * <b>不写这个 bean 会怎样：</b>Spring Boot 不给 {@code RabbitTemplate} 自动配 JSON 转换器，
     * 没显式声明时用的是 {@code SimpleMessageConverter}，它只认 String/byte[]/{@code Serializable}。
     * 事件是 record，不带 {@code Serializable}，于是<b>每一次发布都抛异常</b>——
     * 而这里的异常是被 catch 住只记日志的（评价已落库，抛出去也回滚不了它），
     * 所以在页面上表现为「评分永远不更新」，没有任何报错。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
