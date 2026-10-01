package yumefusaka.envoymart.productservice.mq;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本服务的 RabbitMQ 消息转换器。
 * <p>
 * <b>这个 bean 必须存在，而它缺席时不会报错——只是所有跨服务消费全部失效。</b>
 * Spring Boot 的 {@code RabbitTemplateConfigurer} 只在容器里<b>已有</b>
 * {@code MessageConverter} bean 时才把它装上去，否则 {@code RabbitTemplate} 留着它自己的
 * 默认值 {@code SimpleMessageConverter}：只认 String / byte[] / {@code Serializable}，
 * 而各服务的事件都是 record（不带 {@code Serializable}），发与收都会抛。
 * <p>
 * 踩过的两次：
 * <ul>
 *   <li>{@code CacheEvictConsumer} —— 缓存补偿消息只会在「Redis 删除失败」时才发，
 *       而发送失败被 catch 住只记了一行 error，于是这条通道<b>从来没通过</b>也没人发现；</li>
 *   <li>评价聚合与订单销量 —— 商品评分停在旧值、销量不涨，页面上看不出是消息没到
 *       还是本来就没数据。</li>
 * </ul>
 * order-service 与 payment-service 各自声明了同名的 bean；这份是同一个理由的第三处。
 * 放在 {@code mq} 包而不是某个具体通道的配置类里：它是本服务所有队列共用的，
 * 挂到 {@code CacheEvictConfig} 或 {@code ProductAggregateConfig} 上会让人以为只管那一条。
 */
@Configuration
public class RabbitJsonConfig {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
