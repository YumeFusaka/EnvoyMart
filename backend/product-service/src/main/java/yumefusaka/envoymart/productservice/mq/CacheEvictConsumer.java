package yumefusaka.envoymart.productservice.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.productservice.service.impl.ProductCacheService;

/**
 * 缓存失效的补偿消费者 —— 重试那些第一次没删掉的缓存。
 * <p>
 * <b>它只做一件事：再删一次。</b>删除失败时<b>让它抛出去</b>，由容器按统一配置
 * nack 并投进死信队列——重试耗尽仍删不掉的消息留在那里等人看，
 * 而不是当成功吞掉。这个项目里最危险的失败模式一直是"看起来成功了"。
 * <p>
 * <b>刻意调用 {@code deleteCacheOnly} 而不是 {@code evictProductCache}</b>：
 * 后者在失败时会再发一条补偿消息，从消费者里调它就成了自我循环——
 * 一条删不掉的消息会无限复制自己。
 */
@Slf4j
@Component
public class CacheEvictConsumer {

    private final ProductCacheService productCacheService;

    public CacheEvictConsumer(ProductCacheService productCacheService) {
        this.productCacheService = productCacheService;
    }

    @RabbitListener(queues = CacheEvictConfig.EVICT_QUEUE)
    public void onCacheEvict(CacheEvictEvent event) {
        log.info("[Cache] 收到补偿消息，重试删除: id={} 原因={}", event.productId(), event.reason());
        productCacheService.deleteCacheOnly(event.productId());
        log.info("[Cache] 补偿删除成功: id={}", event.productId());
    }
}
