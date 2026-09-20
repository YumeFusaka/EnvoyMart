package yumefusaka.envoymart.common.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * 生产者确认 —— 让"消息发出去了"这件事有回执。
 * <p>
 * <b>它补的是哪一段</b>：没有确认时，{@code convertAndSend} 把消息交给客户端就返回，
 * 发送方认为成功——而消息可能根本没到 broker（连接断了、broker 重启）。
 * 这是**静默丢失**：调用方看不到任何异常，业务以为事件已经发出去了。
 * <p>
 * 两个回调各管一件事：
 * <ul>
 *   <li>{@code ConfirmCallback} —— 消息**到没到 broker**。{@code ack=false} 时记录原因。</li>
 *   <li>{@code ReturnsCallback} —— 消息到了 broker、但**路由不到任何队列**。
 *       交换机存在而路由键写错时触发，没有它就是无声丢弃。
 *       它要求 {@code mandatory=true}，见各服务的 {@code spring.rabbitmq.template} 配置。</li>
 * </ul>
 * <p>
 * <b>为什么放在 common</b>：回调必须设在 {@code RabbitTemplate} 实例上，而发消息的
 * 有两个服务（order 与 payment）。修在共同经过的这一层，与 {@code InternalFeignConfig}
 * 同样的理由。
 * <p>
 * <b>确认之后仍然不保证不丢</b>：它只覆盖"到 broker"这一段。broker 本身没持久化、
 * 或消费者拿走后处理失败，都还需要各自那层的保障（队列 durable + 死信）。
 * 这三层合起来才是"消息不丢"的完整图景。
 */
@Slf4j
@Component
@ConditionalOnClass(RabbitTemplate.class)
public class RabbitPublisherConfirms {

    public RabbitPublisherConfirms(RabbitTemplate rabbitTemplate) {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("[MQ] 消息未到达 broker: id={} cause={}",
                        correlationData == null ? "unknown" : correlationData.getId(), cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> log.error(
                "[MQ] 消息无法路由到队列: exchange={} routingKey={} reply={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
    }
}
