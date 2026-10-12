package yumefusaka.envoymart.productservice.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.RedisSerializer;
import yumefusaka.envoymart.contract.ProductDetail;
import yumefusaka.envoymart.productservice.cache.ProductLocalCache;
import yumefusaka.envoymart.productservice.projection.ProductProjectionBarrier;
import yumefusaka.envoymart.productservice.projection.ProjectionSourceUnresolvedException;
import java.util.function.LongFunction;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductProjectionCacheBarrierTest {
    @SuppressWarnings("unchecked") private final RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked") private final ValueOperations<String, Object> values = mock(ValueOperations.class);
    private final StringRedisTemplate broadcasts = mock(StringRedisTemplate.class);
    private final ProductProjectionBarrier barrier = mock(ProductProjectionBarrier.class);
    private final ProductLocalCache local = new ProductLocalCache(100, 60);

    private ProductCacheService service() {
        when(redis.opsForValue()).thenReturn(values);
        return new ProductCacheService(redis, local, broadcasts, barrier);
    }

    @Test void 未决miss不执行loader也不写缓存() {
        var service = service();
        doThrow(new ProjectionSourceUnresolvedException("未决")).when(barrier).read(eq(20L), any());
        var loaded = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(() -> service.getOrLoad(20L, () -> { loaded.set(true); return ProductDetail.builder().id(20L).build(); }))
                .isInstanceOf(ProjectionSourceUnresolvedException.class);
        assertThat(loaded).isFalse();
        assertThat(local.get(20L)).isNull();
        verify(redis, never()).execute(any(RedisCallback.class));
    }

    @Test void 严格Redis失败抛给任务且本地副本已失效() {
        var service = service();
        local.fillIfNoInvalidationSince(20L, ProductDetail.builder().id(20L).build(), local.invalidationEpoch());
        doThrow(new IllegalStateException("Redis断连")).when(redis).execute(any(RedisCallback.class));
        assertThatThrownBy(() -> service.invalidateProjection(20L, 6)).hasMessage("Redis断连");
        assertThat(local.get(20L)).isNull();
        verifyNoInteractions(broadcasts);
    }

    @Test void miss回源与回填都发生在稳定屏障内部且旧版本拒绝不填本地() {
        var service = service();
        @SuppressWarnings("unchecked") RedisSerializer<Object> serializer = mock(RedisSerializer.class);
        doReturn(serializer).when(redis).getValueSerializer();
        when(serializer.serialize(any())).thenReturn(new byte[]{1});
        doReturn(0L).when(redis).execute(any(RedisCallback.class));
        var inside = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            inside.set(true);
            try { return call.<LongFunction<ProductDetail>>getArgument(1).apply(6); }
            finally { inside.set(false); }
        }).when(barrier).read(eq(20L), any());
        var result = service.getOrLoad(20L, () -> {
            assertThat(inside).isTrue();
            return ProductDetail.builder().id(20L).build();
        });
        assertThat(result.getId()).isEqualTo(20L);
        assertThat(local.get(20L)).isNull();
        verify(redis).execute(any(RedisCallback.class));
    }
}
