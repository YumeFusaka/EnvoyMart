package yumefusaka.envoymart.orderservice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.orderservice.model.CartItemResponse;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis 缓存包装层：购物车缓存 & 分布式锁
 */
@Service
public class CartCacheService {

    private static final String CART_KEY_PREFIX = "cart:user:";
    private static final String STOCK_LOCK_PREFIX = "stock:lock:";
    private static final long CART_TTL_HOURS = 72;
    private static final long LOCK_WAIT_SECONDS = 3;
    private static final long LOCK_LEASE_SECONDS = 10;

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    public CartCacheService(RedisTemplate<String, Object> redisTemplate,
                            RedissonClient redissonClient,
                            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
    }

    // ========== 购物车缓存 ==========

    @SuppressWarnings("unchecked")
    public List<CartItemResponse> getCachedCart(String userId) {
        String key = cartKey(userId);
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof List<?> list) {
            return list.stream()
                    .filter(CartItemResponse.class::isInstance)
                    .map(CartItemResponse.class::cast)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    public void cacheCart(String userId, List<CartItemResponse> items) {
        redisTemplate.opsForValue().set(cartKey(userId), items, CART_TTL_HOURS, TimeUnit.HOURS);
    }

    public void evictCartCache(String userId) {
        redisTemplate.delete(cartKey(userId));
    }

    // ========== 分布式锁 ==========

    public RLock getStockLock(Long productId) {
        return redissonClient.getLock(STOCK_LOCK_PREFIX + productId);
    }

    /**
     * 尝试加分布式锁，成功返回 true，失败或超时返回 false
     */
    public boolean tryLock(Long productId) {
        RLock lock = getStockLock(productId);
        try {
            return lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void unlock(Long productId) {
        RLock lock = getStockLock(productId);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private String cartKey(String userId) {
        return CART_KEY_PREFIX + userId;
    }
}
