package yumefusaka.envoymart.productservice.mq;

/**
 * 缓存失效补偿事件。
 *
 * @param productId 需要失效的商品 id
 * @param reason    当初删除失败的原因（只用于排查，消费端不依赖它）
 */
public record CacheEvictEvent(Long productId, String reason) {
}
