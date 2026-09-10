package yumefusaka.envoymart.productservice.service.impl;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.productservice.model.ProductResponse;

import java.util.concurrent.TimeUnit;

/**
 * 商品缓存服务：热点商品数据 Redis 缓存，减少 DB 查询
 */
@Service
public class ProductCacheService {

    private static final String PRODUCT_KEY_PREFIX = "product:detail:";
    private static final long PRODUCT_TTL_HOURS = 24;

    private final RedisTemplate<String, Object> redisTemplate;

    public ProductCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public ProductResponse getCachedProduct(Long id) {
        return (ProductResponse) redisTemplate.opsForValue().get(PRODUCT_KEY_PREFIX + id);
    }

    public void cacheProduct(ProductResponse product) {
        redisTemplate.opsForValue().set(
                PRODUCT_KEY_PREFIX + product.getId(),
                product,
                PRODUCT_TTL_HOURS, TimeUnit.HOURS);
    }

    public void evictProductCache(Long id) {
        redisTemplate.delete(PRODUCT_KEY_PREFIX + id);
    }
}
