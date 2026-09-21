package yumefusaka.envoymart.orderservice.mq;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 事件驱动配置：订单相关事件交换机、队列与绑定
 */
@Configuration
public class OrderEventConfig {

    /**
     * 事件对象用 JSON 序列化；默认的 SimpleMessageConverter 只支持 String/byte[]/Serializable。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    // ========== 交换机 ==========
    public static final String ORDER_EXCHANGE = "envoymart.order";
    public static final String DEAD_LETTER_EXCHANGE = "envoymart.dlx";

    // ========== 队列 ==========
    public static final String ORDER_CREATED_QUEUE = "order.created.queue";
    public static final String PAYMENT_COMPLETED_QUEUE = "payment.completed.queue";
    public static final String ORDER_DLX_QUEUE = "order.dlx.queue";

    // ========== 路由键 ==========
    public static final String ORDER_CREATED_KEY = "order.created";
    public static final String PAYMENT_COMPLETED_KEY = "payment.completed";

    @Bean
    public TopicExchange orderExchange() {
        return ExchangeBuilder.topicExchange(ORDER_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 死信交换机必须是 topic。
     * <p>
     * 业务队列声明的死信路由键是 {@code dead.order.created} / {@code dead.payment.completed}，
     * 而这里原先绑的是 {@code dead.#} —— {@code #} 是 topic 通配符，direct 交换机只做精确匹配，
     * 结果死信永远路由不到队列，被 broker 静默丢弃。
     */
    @Bean
    public TopicExchange deadLetterExchange() {
        return ExchangeBuilder.topicExchange(DEAD_LETTER_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public Queue orderCreatedQueue() {
        return QueueBuilder.durable(ORDER_CREATED_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", "dead.order.created")
                .build();
    }

    @Bean
    public Queue paymentCompletedQueue() {
        return QueueBuilder.durable(PAYMENT_COMPLETED_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", "dead.payment.completed")
                .build();
    }

    @Bean
    public Queue orderDlxQueue() {
        return QueueBuilder.durable(ORDER_DLX_QUEUE).build();
    }

    // 下面三个 Binding 的**每个参数都显式 @Qualifier**，不靠形参名消歧。
    // 这里有两个 TopicExchange（orderExchange / deadLetterExchange）与三个 Queue，
    // 原本第一个参数只写形参名，那是依赖字节码里的 MethodParameters 属性——
    // Maven 传了 -parameters 所以能跑，IDE 用自己的编译设置写同一份 target/classes 时
    // 不传这个标志，参数名就丢了，启动直接报「expected single matching bean but found 2」。
    // 症状是"时好时坏、mvn clean 有时能修"，取决于最后写 class 的是谁。

    @Bean
    public Binding orderCreatedBinding(
            @Qualifier("orderExchange") TopicExchange orderExchange,
            @Qualifier("orderCreatedQueue") Queue orderCreatedQueue) {
        return BindingBuilder.bind(orderCreatedQueue).to(orderExchange).with(ORDER_CREATED_KEY);
    }

    @Bean
    public Binding paymentCompletedBinding(
            @Qualifier("orderExchange") TopicExchange orderExchange,
            @Qualifier("paymentCompletedQueue") Queue paymentCompletedQueue) {
        return BindingBuilder.bind(paymentCompletedQueue).to(orderExchange).with(PAYMENT_COMPLETED_KEY);
    }

    @Bean
    public Binding dlxBinding(
            @Qualifier("deadLetterExchange") TopicExchange deadLetterExchange,
            @Qualifier("orderDlxQueue") Queue orderDlxQueue) {
        return BindingBuilder.bind(orderDlxQueue).to(deadLetterExchange).with("dead.#");
    }
}
