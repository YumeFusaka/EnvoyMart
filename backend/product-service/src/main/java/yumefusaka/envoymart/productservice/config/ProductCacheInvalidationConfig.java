package yumefusaka.envoymart.productservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import yumefusaka.envoymart.productservice.cache.ProductLocalCache;

import java.nio.charset.StandardCharsets;

/**
 * 本地缓存的跨实例失效广播。
 *
 * <h3>为什么必须有它</h3>
 * 本地缓存是<b>每实例一份</b>的。商品变更时如果只删 Redis 的 key，
 * 别的实例手里那份还在，会一直返回旧值直到本地 TTL 到期——
 * 而商品详情里带<b>库存</b>，前端拿它做加购联动，旧库存会直接误导用户。
 *
 * <h3>为什么用 Redis pub/sub 而不是 MQ</h3>
 * 这个项目已经有 RabbitMQ，但失效通知是**幂等的、可丢的**（丢了还有本地 TTL 兜底），
 * 不需要持久化与投递保证；而它要求<b>延迟极低</b>——广播晚一秒，那一秒的读就都是旧值。
 * pub/sub 是即发即弃的广播语义，正合这个场景；走 MQ 反而要建队列、绑交换机、
 * 还要处理消费堆积，代价与收益不匹配。
 *
 * <h3>它保证不了什么</h3>
 * <ul>
 *   <li><b>不保证送达</b>：订阅端断连期间的消息直接丢。所以本地 TTL 不是可选项——
 *       它是这条链路唯一的最终兜底。</li>
 *   <li><b>不保证顺序</b>：同一商品的两次失效可能乱序到达，但两次都是"删掉"，
 *       幂等，所以无所谓。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class ProductCacheInvalidationConfig {

    /** 失效通知频道。所有 product-service 实例都订阅它。 */
    public static final String INVALIDATION_CHANNEL = "product:cache:invalidate";

    @Bean
    public ChannelTopic productCacheInvalidationTopic() {
        return new ChannelTopic(INVALIDATION_CHANNEL);
    }

    /**
     * 订阅失效通知并清掉本实例的本地缓存。
     * <p>
     * 容器由 Spring 托管生命周期、自动启停；Redis 不可用时它会自行重连，
     * 期间收到的失效会丢——这正是本地 TTL 存在的理由。
     */
    @Bean
    public RedisMessageListenerContainer productCacheInvalidationContainer(
            RedisConnectionFactory connectionFactory,
            ProductLocalCache localCache,
            ChannelTopic productCacheInvalidationTopic) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8).trim();
            // 去掉可能存在的 JSON 引号：发布端如果误用了带 Jackson 序列化器的 RedisTemplate，
            // 字符串 1 会变成 "1"。**这个防御是有代价的教训换来的**——
            // 早先正是这个不匹配让广播"看起来在跑、实际一条都没生效"。
            if (body.length() >= 2 && body.startsWith("\"") && body.endsWith("\"")) {
                body = body.substring(1, body.length() - 1);
            }
            try {
                localCache.invalidate(Long.parseLong(body));
                log.debug("[LocalCache] 收到失效广播，已清除本地副本 id={}", body);
            } catch (NumberFormatException e) {
                // 一条坏消息不该影响订阅通道的其他消息，记下来继续
                log.warn("[LocalCache] 失效广播内容无法解析，已忽略: {}", body);
            }
        }, productCacheInvalidationTopic);
        return container;
    }
}
