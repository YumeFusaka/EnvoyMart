package yumefusaka.envoymart.productservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import yumefusaka.envoymart.productservice.cache.ProductLocalCache;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * U66 的回归：Redis 握手失败不得把异常抛到启动流程里。
 * <p>
 * <b>为什么不去启动整个应用</b>：这条测试要隔离的变量只有一个——「监听容器连不上 Redis 时，
 * 异常会不会冒到调用方」。整应用启动会额外拖进 H2 + Seata 数据源代理（Seata 的 AT 模式
 * 不接受 H2，测试里必然先挂在数据源上），那测的是另一个问题，信号被淹掉。
 * 这里直接拿一个<b>真实的 Lettuce 连接工厂</b>指向不可达地址，调
 * {@code startInvalidationListener}，断言它既不抛异常、也不把应用的启动拖死——
 * 这正是生产里 `ApplicationReadyEvent` 那一刻发生的事。
 * <p>
 * <b>不碰真实 Redis</b>：连的是没人监听的端口或不可路由地址，比停掉真 Redis 更快、
 * 也不影响并行验证。
 * <p>
 * <b>边界的反面也一起钉住</b>：容器确实"启动了"（没有抛异常），但从未真正连上——
 * 所以此时收到失效广播是不会清缓存的，本地 TTL 是唯一的兜底。这一条写进断言里，
 * 免得以后有人以为"启动不报错=失效广播在工作"。
 */
class ProductCacheInvalidationConfigStartupTest {

    /** 端口无人监听 —— 连接被立刻拒绝，复现"Redis 不可用"最快的一档。 */
    private static LettuceConnectionFactory deadRedis() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 16399);
        factory.setTimeout(1000L);
        // 必须显式初始化：afterPropertiesSet 才是真正建连接的地方，
        // 这正是生产里启动期那一次握手的等价物
        factory.afterPropertiesSet();
        return factory;
    }

    /**
     * 黑洞地址（不可路由，连接会一直被丢弃直到超时）—— 复现 U66 报告里"握手超 1 秒"那一档。
     * 与"端口无人监听"（立刻拒绝）不同，这条路径要走满 1 秒连接超时，正是当初机器忙时的形态。
     */
    private static LettuceConnectionFactory blackHoleRedis() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("10.255.255.1", 6379);
        factory.setTimeout(1000L);
        factory.afterPropertiesSet();
        return factory;
    }

    @Test
    void redis握手失败时监听容器启动不抛异常() {
        assertStartsWithoutThrowing(deadRedis());
    }

    @Test
    void redis握手超时一秒时监听容器启动不抛异常() {
        assertStartsWithoutThrowing(blackHoleRedis());
    }

    private void assertStartsWithoutThrowing(LettuceConnectionFactory connectionFactory) {
        ProductCacheInvalidationConfig config = new ProductCacheInvalidationConfig();
        ProductLocalCache localCache = new ProductLocalCache(100, 60);

        try {
            RedisMessageListenerContainer container = config.productCacheInvalidationContainer(
                    connectionFactory, localCache,
                    new ChannelTopic(ProductCacheInvalidationConfig.INVALIDATION_CHANNEL));

            assertThat(container.isAutoStartup())
                    .as("容器必须已从 Spring 自动启动名单里摘出来，否则异常仍会冒到启动流程")
                    .isFalse();

            GenericApplicationContext context = new GenericApplicationContext();
            // 注册名取自 Config 的常量（唯一来源）：两边各写一份字符串必然分叉——
            // U66 改成「按名字取」时测试漏改，于是这条断言一直在验一个不存在的 bean 名。
            context.getBeanFactory().registerSingleton(
                    ProductCacheInvalidationConfig.CONTAINER_BEAN_NAME, container);
            // refresh 会触发 SmartLifecycle 的自动启动——autoStartup=false 时必须静默跳过，
            // 这一步本身就是"应用能起来"的本地等价物
            context.refresh();

            ApplicationReadyEvent event = new ApplicationReadyEvent(
                    new org.springframework.boot.SpringApplication(
                            yumefusaka.envoymart.productservice.ProductServiceApplication.class),
                    new String[0], context, Duration.ZERO);

            assertThatCode(() -> config.startInvalidationListener(event))
                    .as("Redis 连不上时启动监听器必须被接住，不能把异常抛给启动流程")
                    .doesNotThrowAnyException();

            assertThat(container.isRunning())
                    .as("容器处于运行态（启动成功，只是还没连上），失效广播靠它周期性重连")
                    .isTrue();

            context.close();
        } finally {
            connectionFactory.destroy();
        }
    }
}