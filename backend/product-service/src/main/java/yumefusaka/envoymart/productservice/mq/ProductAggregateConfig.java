package yumefusaka.envoymart.productservice.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 商品派生数据的入站通道：别人的事实变了，我们这份冗余要跟着变。
 * <p>
 * 两条队列各对应一个来源：
 * <ul>
 *   <li>{@code review.aggregate.changed} —— 来自 review-service。商品的评分与评论数
 *       冗余在 {@code product_spu} 上，因为列表页要按评分排序，那个聚合查询落不到索引上。</li>
 *   <li>{@code order.paid} —— 来自 order-service。销量同理，它是商品卡上的一等信息，
 *       不能每次现算（那是跨库的 COUNT）。</li>
 * </ul>
 * <p>
 * <b>两个来源交换机（{@code envoymart.review} / {@code envoymart.order}）在这里也声明一遍。</b>
 * 声明是幂等的（同名、同类型、同持久化），生产者那边已经声明过，这里再声明只是保证
 * 「消费者先启动、生产者还没起」时绑定不会失败——否则那条绑定会被 broker 静默丢弃，
 * 事后生产者起来了、发出去的消息也没有队列可路由。
 * <p>
 * <b>死信用自己的交换机</b>，不蹭 order-service 的 {@code envoymart.dlx}：那套死信队列
 * 由 order-service 自己消费，商品侧处理不了的消息混进去，会跑到别人的队列里。
 */
@Configuration
public class ProductAggregateConfig {

    public static final String REVIEW_EXCHANGE = "envoymart.review";
    public static final String ORDER_EXCHANGE = "envoymart.order";
    public static final String PRODUCT_DLX_EXCHANGE = "envoymart.product.dlx";

    public static final String REVIEW_AGGREGATE_QUEUE = "product.review.aggregate.queue";
    public static final String SALES_QUEUE = "product.sales.queue";
    public static final String AGGREGATE_DLX_QUEUE = "product.aggregate.dlx.queue";

    public static final String REVIEW_AGGREGATE_KEY = "review.aggregate.changed";
    public static final String ORDER_PAID_KEY = "order.paid";

    public static final String DEAD_REVIEW_AGGREGATE_KEY = "dead.review.aggregate";
    public static final String DEAD_SALES_KEY = "dead.sales";

    @Bean
    public TopicExchange reviewExchange() {
        return new TopicExchange(REVIEW_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(ORDER_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange productDlxExchange() {
        return new TopicExchange(PRODUCT_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue reviewAggregateQueue() {
        return QueueBuilder.durable(REVIEW_AGGREGATE_QUEUE)
                .withArgument("x-dead-letter-exchange", PRODUCT_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_REVIEW_AGGREGATE_KEY)
                .build();
    }

    @Bean
    public Queue salesQueue() {
        return QueueBuilder.durable(SALES_QUEUE)
                .withArgument("x-dead-letter-exchange", PRODUCT_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_SALES_KEY)
                .build();
    }

    @Bean
    public Queue aggregateDlxQueue() {
        return QueueBuilder.durable(AGGREGATE_DLX_QUEUE).build();
    }

    // 下面每个参数都显式 @Qualifier，不靠形参名消歧。
    // 这里有三个 TopicExchange 与三个 Queue，Spring 只能靠 bean 名或 @Qualifier 区分。
    // 只写形参名是在依赖字节码里的 MethodParameters 属性——Maven 传了 -parameters
    // 所以能跑，IDE 用自己的编译设置写同一份 target/classes 时不传这个标志，参数名
    // 就丢了，启动直接报「expected single matching bean but found 3」。
    // 同一个坑在 CacheEvictConfig 里已经踩过一次，注释也留在那里。

    @Bean
    public Binding reviewAggregateBinding(
            @Qualifier("reviewExchange") TopicExchange reviewExchange,
            @Qualifier("reviewAggregateQueue") Queue reviewAggregateQueue) {
        return BindingBuilder.bind(reviewAggregateQueue).to(reviewExchange).with(REVIEW_AGGREGATE_KEY);
    }

    @Bean
    public Binding salesBinding(
            @Qualifier("orderExchange") TopicExchange orderExchange,
            @Qualifier("salesQueue") Queue salesQueue) {
        return BindingBuilder.bind(salesQueue).to(orderExchange).with(ORDER_PAID_KEY);
    }

    @Bean
    public Binding aggregateDlxBinding(
            @Qualifier("productDlxExchange") TopicExchange productDlxExchange,
            @Qualifier("aggregateDlxQueue") Queue aggregateDlxQueue) {
        return BindingBuilder.bind(aggregateDlxQueue).to(productDlxExchange).with("dead.#");
    }
}
