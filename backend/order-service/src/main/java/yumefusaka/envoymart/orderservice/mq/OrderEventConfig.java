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
    /**
     * 库存回补补偿队列。
     * <p>
     * <b>是队列，不是一张「待办表」。</b>本服务自己发、自己收 —— 看起来绕，
     * 但换来的正是「持久、可重试、会进死信」这三件重试必须具备的性质：
     * 内存里的重试队列重启就没了，而「订单关了、库存没还」丢了就是永久的库存差额。
     * <p>
     * 独立于 payment.completed 那条线：那条是「通知下游」，这条是「我必须做成」，
     * 语义不同，重试策略也就不该共用。
     */
    public static final String STOCK_RESTORE_QUEUE = "stock.restore.compensation.queue";

    // ========== 路由键 ==========
    public static final String ORDER_CREATED_KEY = "order.created";
    public static final String PAYMENT_COMPLETED_KEY = "payment.completed";
    /** 回补库存的补偿路由键。见 {@link StockRestoreRequest} */
    public static final String STOCK_RESTORE_KEY = "stock.restore.compensation";

    /**
     * 订单真的变成「已支付」了 —— 与 {@code payment.completed} 的方向相反：
     * 那条是 payment-service 发进来、这个服务收，这条是这个服务发出去、商品服务收（累加销量）。
     * <p>
     * 本服务<b>不为自己发出去的事件声明队列</b>：队列属于消费方（product-service 那边声明）。
     * 在这里也建一条队列并绑定，等于多出一个没人消费的"幽灵队列"——
     * 消息进去就堆着，看起来像"事件没人处理"，实际是重复声明出来的。
     */
    public static final String ORDER_PAID_KEY = "order.paid";

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

    @Bean
    public Queue stockRestoreQueue() {
        return QueueBuilder.durable(STOCK_RESTORE_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", "dead.stock.restore")
                .build();
    }

    // 下面几个 Binding 的**每个参数都显式 @Qualifier**，不靠形参名消歧。
    // 这里有多个 TopicExchange 与多个 Queue，只写形参名是依赖字节码里的
    // MethodParameters 属性——Maven 传了 -parameters 所以能跑，IDE 用自己的
    // 编译设置写同一份 target/classes 时不传这个标志，参数名就丢了，
    // 启动直接报「expected single matching bean but found 2」。
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

    @Bean
    public Binding stockRestoreBinding(
            @Qualifier("orderExchange") TopicExchange orderExchange,
            @Qualifier("stockRestoreQueue") Queue stockRestoreQueue) {
        return BindingBuilder.bind(stockRestoreQueue).to(orderExchange).with(STOCK_RESTORE_KEY);
    }
}