package yumefusaka.envoymart.orderservice.mq;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 事件驱动配置：订单相关事件交换机、队列与绑定
 */
@Configuration
public class OrderEventConfig {

    // ========== 交换机 ==========
    public static final String ORDER_EXCHANGE = "envoymart.order";
    public static final String DEAD_LETTER_EXCHANGE = "envoymart.dlx";

    // ========== 队列 ==========
    public static final String ORDER_CREATED_QUEUE = "order.created.queue";
    public static final String PAYMENT_COMPLETED_QUEUE = "payment.completed.queue";
    public static final String STOCK_UPDATED_QUEUE = "stock.updated.queue";
    public static final String ORDER_DLX_QUEUE = "order.dlx.queue";

    // ========== 路由键 ==========
    public static final String ORDER_CREATED_KEY = "order.created";
    public static final String PAYMENT_COMPLETED_KEY = "payment.completed";
    public static final String STOCK_UPDATED_KEY = "stock.updated";

    @Bean
    public TopicExchange orderExchange() {
        return ExchangeBuilder.topicExchange(ORDER_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE)
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
    public Queue stockUpdatedQueue() {
        return QueueBuilder.durable(STOCK_UPDATED_QUEUE).build();
    }

    @Bean
    public Queue orderDlxQueue() {
        return QueueBuilder.durable(ORDER_DLX_QUEUE).build();
    }

    @Bean
    public Binding orderCreatedBinding(TopicExchange orderExchange, Queue orderCreatedQueue) {
        return BindingBuilder.bind(orderCreatedQueue).to(orderExchange).with(ORDER_CREATED_KEY);
    }

    @Bean
    public Binding paymentCompletedBinding(TopicExchange orderExchange, Queue paymentCompletedQueue) {
        return BindingBuilder.bind(paymentCompletedQueue).to(orderExchange).with(PAYMENT_COMPLETED_KEY);
    }

    @Bean
    public Binding stockUpdatedBinding(TopicExchange orderExchange, Queue stockUpdatedQueue) {
        return BindingBuilder.bind(stockUpdatedQueue).to(orderExchange).with(STOCK_UPDATED_KEY);
    }

    @Bean
    public Binding dlxBinding(DirectExchange deadLetterExchange, Queue orderDlxQueue) {
        return BindingBuilder.bind(orderDlxQueue).to(deadLetterExchange).with("dead.#");
    }
}
