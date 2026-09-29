package yumefusaka.envoymart.orderservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.orderservice.client.ProductClient;
import yumefusaka.envoymart.orderservice.entity.CartItemEntity;
import yumefusaka.envoymart.orderservice.mapper.CartItemMapper;
import yumefusaka.envoymart.orderservice.model.AddCartItemRequest;
import yumefusaka.envoymart.orderservice.model.CartItemResponse;
import yumefusaka.envoymart.orderservice.model.SkuSnapshot;
import yumefusaka.envoymart.orderservice.model.UpdateCartItemRequest;
import yumefusaka.envoymart.orderservice.service.CartService;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CartServiceImpl implements CartService {

    private static final int MAX_QUANTITY = 99;
    private static final int SELECTED = 1;
    private static final int UNSELECTED = 0;
    private static final int STATUS_ON = 1;

    private final CartItemMapper cartItemMapper;
    private final ProductClient productClient;
    private final CartCacheService cartCacheService;

    public CartServiceImpl(CartItemMapper cartItemMapper,
                           ProductClient productClient,
                           CartCacheService cartCacheService) {
        this.cartItemMapper = cartItemMapper;
        this.productClient = productClient;
        this.cartCacheService = cartCacheService;
    }

    @Override
    public List<CartItemResponse> list(String userId) {
        // 缓存未命中时它返回的是**空列表**而不是 null，所以判空必须用 isEmpty ——
        // 写成 `!= null` 会让这个方法永远返回空车，而库里明明有数据。
        // 改造中实际踩到：加购返回正常，一读购物车就是空的
        List<CartItemResponse> cached = cartCacheService.getCachedCart(userId);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        List<CartItemEntity> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .orderByDesc(CartItemEntity::getId));

        List<CartItemResponse> result = assemble(items);
        cartCacheService.cacheCart(userId, result);
        return result;
    }

    @Override
    public CartItemResponse add(String userId, AddCartItemRequest request) {
        SkuSnapshot sku = requireSku(request.getSkuId());

        CartItemEntity existing = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .eq(CartItemEntity::getSkuId, request.getSkuId()));

        if (existing == null) {
            CartItemEntity entity = new CartItemEntity();
            entity.setUserId(userId);
            entity.setSpuId(sku.getSpuId());
            entity.setSkuId(sku.getId());
            entity.setQuantity(request.getQuantity());
            entity.setSelected(SELECTED);
            entity.setCreatedAt(Times.now());
            entity.setUpdatedAt(Times.now());
            try {
                cartItemMapper.insert(entity);
            } catch (DuplicateKeyException e) {
                // 并发加购撞上了 (user_id, sku_id) 唯一键。改成累加而不是报错：
                // 两个请求同时点「加入购物车」，用户的预期是数量加两次
                return mergeQuantity(userId, request.getSkuId(), request.getQuantity(), sku);
            }
            cartCacheService.evictCartCache(userId);
            return toResponse(entity, sku);
        }

        CartItemResponse merged = mergeQuantity(userId, request.getSkuId(), request.getQuantity(), sku);
        return merged;
    }

    /** 把数量加上去并封顶。封顶而不是报错：用户连点几次不该收到一个错误弹窗 */
    private CartItemResponse mergeQuantity(String userId, Long skuId, int delta, SkuSnapshot sku) {
        CartItemEntity entity = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .eq(CartItemEntity::getSkuId, skuId));
        if (entity == null) {
            throw new IllegalStateException("购物车条目已不存在，请重试");
        }
        entity.setQuantity(Math.min(entity.getQuantity() + delta, MAX_QUANTITY));
        entity.setUpdatedAt(Times.now());
        cartItemMapper.updateById(entity);
        cartCacheService.evictCartCache(userId);
        return toResponse(entity, sku);
    }

    @Override
    public CartItemResponse updateQuantity(String userId, Long id, UpdateCartItemRequest request) {
        CartItemEntity entity = requireOwned(userId, id);
        entity.setQuantity(request.getQuantity());
        entity.setUpdatedAt(Times.now());
        cartItemMapper.updateById(entity);
        cartCacheService.evictCartCache(userId);
        return toResponse(entity, requireSku(entity.getSkuId()));
    }

    @Override
    public void remove(String userId, Long id) {
        requireOwned(userId, id);
        cartItemMapper.deleteById(id);
        cartCacheService.evictCartCache(userId);
    }

    @Override
    public CartItemResponse setSelected(String userId, Long id, boolean selected) {
        CartItemEntity entity = requireOwned(userId, id);
        entity.setSelected(selected ? SELECTED : UNSELECTED);
        entity.setUpdatedAt(Times.now());
        cartItemMapper.updateById(entity);
        cartCacheService.evictCartCache(userId);
        return toResponse(entity, requireSku(entity.getSkuId()));
    }

    /**
     * 一次批量取 SKU 快照。
     * <p>
     * 不是每个条目查一次：一个装满的购物车十件商品就是十次跨服务调用，
     * 而它们本来可以合成一次。
     */
    private List<CartItemResponse> assemble(List<CartItemEntity> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        Map<Long, SkuSnapshot> skus = fetchSkus(
                items.stream().map(CartItemEntity::getSkuId).distinct().toList());
        return items.stream()
                .map(item -> toResponse(item, skus.get(item.getSkuId())))
                .toList();
    }

    private Map<Long, SkuSnapshot> fetchSkus(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        Result<List<SkuSnapshot>> response = productClient.getSkus(skuIds);
        if (response == null || response.getData() == null) {
            // 商品服务不可用时**必须抛错**，不能静默当成空车：
            // 用户会以为自己的购物车被清空了，那比一句错误提示糟糕得多
            throw new IllegalStateException("商品服务暂时不可用，请稍后再试");
        }
        return response.getData().stream()
                .collect(Collectors.toMap(SkuSnapshot::getId, Function.identity(), (a, b) -> a));
    }

    private SkuSnapshot requireSku(Long skuId) {
        SkuSnapshot sku = fetchSkus(List.of(skuId)).get(skuId);
        if (sku == null) {
            throw new IllegalArgumentException("商品规格不存在");
        }
        if (sku.getStatus() == null || sku.getStatus() != STATUS_ON) {
            throw new IllegalStateException("商品已下架");
        }
        return sku;
    }

    /**
     * 条目归属校验。
     * <p>
     * 「条目不存在」与「条目不属于你」返回同一句话：区分开来等于告诉调用方
     * 哪些 id 是有效的，越权探测就此变成一次盲注。
     */
    private CartItemEntity requireOwned(String userId, Long id) {
        CartItemEntity entity = id == null ? null : cartItemMapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(userId)) {
            throw new IllegalArgumentException("购物车条目不存在");
        }
        return entity;
    }

    private CartItemResponse toResponse(CartItemEntity item, SkuSnapshot sku) {
        if (sku == null) {
            // SKU 已被删除：给一个"失效"的占位而不是把整行抹掉 ——
            // 直接消失的条目在用户看来像系统出了问题
            return CartItemResponse.builder()
                    .id(item.getId())
                    .spuId(item.getSpuId())
                    .skuId(item.getSkuId())
                    .name("商品已下架")
                    .quantity(item.getQuantity())
                    .price(0L)
                    .subtotal(0L)
                    .stock(0)
                    .selected(false)
                    .available(false)
                    .build();
        }

        long price = sku.getPrice() == null ? 0L : sku.getPrice();
        int quantity = item.getQuantity() == null ? 0 : item.getQuantity();
        int stock = sku.getStock() == null ? 0 : sku.getStock();
        boolean onSale = sku.getStatus() != null && sku.getStatus() == STATUS_ON;

        return CartItemResponse.builder()
                .id(item.getId())
                .spuId(item.getSpuId())
                .skuId(item.getSkuId())
                .name(sku.getSpuName())
                .specText(sku.getSpecText())
                .image(sku.getImage())
                .price(price)
                .quantity(quantity)
                .stock(stock)
                .subtotal(price * quantity)
                .selected(item.getSelected() != null && item.getSelected() == SELECTED)
                .available(onSale && stock > 0)
                .build();
    }
}
