package yumefusaka.envoymart.productservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
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
     * <b>启动不由容器自动完成，而是等应用就绪后手动 start()。</b>
     * 原因是这里的连接超时（{@code spring.data.redis.connect-timeout}）全局只有 1 秒，
     * 那是给<b>读路径</b>定的降级速度，却同时管着启动期握手：机器忙的时候（同时启多个服务、
     * 跑 mvn install）握手可能超过 1 秒，自动启动的容器会当场抛
     * {@code RedisConnectionFailureException} 并让整个进程退出——症状看着像"Redis 挂了"，
     * 实际 Redis 好好的。而这条链路本来就只是"加速层"的跨实例失效广播，
     * 丢了还有本地 TTL 兜底，没有任何理由让它决定进程生死。
     * <p>
     * {@code setRecoveryInterval} 是配套的第二重保险：即使 start() 时 Redis 不可用，
     * 容器也只是记日志、按周期自己重连，不再把异常抛到启动流程里。
     * <p>
     * 订阅端断连期间的消息仍然会丢——这正是本地 TTL 存在的理由，与启动方式无关。
     */
    /**
     * 失效监听容器的 bean 名。<b>唯一来源</b>：{@code startInvalidationListener} 按它取容器，
     * 测试也按它注册——两边各写一份字符串必然分叉（U66 修完就漏改过测试，
     * 于是测试一直在验一个不存在的 bean 名）。
     */
    public static final String CONTAINER_BEAN_NAME = "productCacheInvalidationContainer";

    @Bean(CONTAINER_BEAN_NAME)
    public RedisMessageListenerContainer productCacheInvalidationContainer(
            RedisConnectionFactory connectionFactory,
            ProductLocalCache localCache,
            ChannelTopic productCacheInvalidationTopic) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 关键：从 Spring 的自动启动管理里摘出来。留在里面时，容器的启动异常会顺着
        // DefaultLifecycleProcessor 冒到 SpringApplication.run()，把整个进程带下去——
        // 这正是 U66 的现象。摘出来之后，启动时机由下面的就绪事件接管。
        container.setAutoStartup(false);
        // 周期性重连：Redis 暂时不可用时不把异常抛给启动流程，容器自己恢复
        container.setRecoveryInterval(5_000L);
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

    /**
     * 应用就绪后再拉起监听容器，并把启动期失败降级为一行日志。
     * <p>
     * 用 {@link ApplicationReadyEvent} 而不是 {@code SmartLifecycle} 的自动启动：
     * 只要容器还留在 Spring 的自动启动管理里，它的启动异常就会顺着
     * {@code DefaultLifecycleProcessor} 冒到 {@code SpringApplication.run()}，
     * 照样终止进程。从自动启动名单里摘出来、在就绪事件里自己 start，
     * 异常才真正落在我们可以接住的位置。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startInvalidationListener(ApplicationReadyEvent event) {
        // 按**名字**取，不能按类型取：容器里同时存在 Spring Boot 自动配置的那个
        // RedisMessageListenerContainer，按类型取会撞 NoUniqueBeanDefinitionException——
        // 报的是「找到两个候选」，而真正要做的事只是「把这一条我们自己建的那一个拉起来」。
        // 按名字取还顺带保证了拿到的一定是这里定义的、绑定本主题的那个容器
        RedisMessageListenerContainer container =
                event.getApplicationContext().getBean(
                        CONTAINER_BEAN_NAME, RedisMessageListenerContainer.class);
        try {
            container.start();
            log.info("[LocalCache] 失效广播监听已启动");
        } catch (RuntimeException e) {
            // 降级不是失败：本地 TTL 是这条链路唯一的最终兜底，
            // 监听器晚一点起来只意味着这段时间内的跨实例失效要多等一个 TTL
            log.warn("[LocalCache] 失效广播监听启动失败，本实例按本地 TTL 兜底（不影响服务可用）: {}",
                    e.getMessage());
        }
    }
}
