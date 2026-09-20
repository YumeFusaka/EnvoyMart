package yumefusaka.envoymart.productservice.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 缓存失效的补偿通道。
 * <p>
 * <b>它补的是哪一段</b>：商品缓存正常的失效是<b>同步删除</b>——库存扣减后立刻删，
 * 保证下一个读请求拿到的不是旧值。但删除本身可能失败（Redis 抖动、连接被断），
 * 而缓存是派生数据：<b>它的失效失败不该阻断库存扣减</b>。
 * <p>
 * 所以失败时不再向上抛，而是落一条消息到这里，由消费者重试删除。
 * 队列与交换机都 durable，消费失败按各服务的统一配置进死信——
 * 重试耗尽仍删不掉时消息留在死信队列里，而不是静默消失。
 * <p>
 * <b>这套机制能保证什么、不能保证什么</b>：它保证"删除动作会被重试"，
 * 不保证"重试一定成功"——Redis 长时间不可用时会一路进死信，需要人看。
 * 真正的兜底是缓存的 TTL：即使这条链路全挂，条目也会自然过期。
 * 三者（同步删 / 消息重试 / TTL）叠起来才是完整的失效保证。
 */
@Configuration
public class CacheEvictConfig {

    public static final String CACHE_EXCHANGE = "envoymart.cache";
    public static final String CACHE_DLX_EXCHANGE = "envoymart.cache.dlx";

    public static final String EVICT_QUEUE = "product.cache.evict.queue";
    public static final String EVICT_DLX_QUEUE = "product.cache.evict.dlx.queue";

    public static final String EVICT_KEY = "cache.evict.product";

    @Bean
    public TopicExchange cacheExchange() {
        return new TopicExchange(CACHE_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange cacheDlxExchange() {
        return new TopicExchange(CACHE_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue cacheEvictQueue() {
        return QueueBuilder.durable(EVICT_QUEUE)
                .withArgument("x-dead-letter-exchange", CACHE_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", "dead.cache.evict")
                .build();
    }

    @Bean
    public Queue cacheEvictDlxQueue() {
        return QueueBuilder.durable(EVICT_DLX_QUEUE).build();
    }

    @Bean
    public Binding cacheEvictBinding(TopicExchange cacheExchange,
                                     @org.springframework.beans.factory.annotation.Qualifier("cacheEvictQueue")
                                     Queue cacheEvictQueue) {
        return BindingBuilder.bind(cacheEvictQueue).to(cacheExchange).with(EVICT_KEY);
    }

    @Bean
    public Binding cacheEvictDlxBinding(TopicExchange cacheDlxExchange,
                                        @org.springframework.beans.factory.annotation.Qualifier("cacheEvictDlxQueue")
                                        Queue cacheEvictDlxQueue) {
        return BindingBuilder.bind(cacheEvictDlxQueue).to(cacheDlxExchange).with("dead.cache.evict");
    }
}
