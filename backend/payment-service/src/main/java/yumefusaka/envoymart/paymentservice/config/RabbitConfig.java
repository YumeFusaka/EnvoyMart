package yumefusaka.envoymart.paymentservice.config;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    /**
     * 支付完成事件是对象消息，需要 JSON 序列化；
     * 默认的 SimpleMessageConverter 只支持 String/byte[]/Serializable。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
