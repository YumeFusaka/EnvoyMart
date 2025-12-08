package yumefusaka.envoymart.cart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.cart.mapper.CartItemMapper;
import yumefusaka.envoymart.cart.model.entity.CartItemEntity;
import yumefusaka.envoymart.cart.model.request.AddCartItemRequest;
import yumefusaka.envoymart.cart.model.request.UpdateCartItemRequest;
import yumefusaka.envoymart.cart.model.response.CartItemResponse;
import yumefusaka.envoymart.cart.service.CartService;
import yumefusaka.envoymart.product.model.entity.ProductEntity;
import yumefusaka.envoymart.product.service.ProductService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CartServiceImpl implements CartService {

    private final CartItemMapper cartItemMapper;
    private final ProductService productService;

    public CartServiceImpl(CartItemMapper cartItemMapper, ProductService productService) {
        this.cartItemMapper = cartItemMapper;
        this.productService = productService;
    }

    @Override
    @Transactional
    public CartItemResponse addItem(String userId, AddCartItemRequest request) {
        productService.requireEntity(request.getProductId());
        CartItemEntity existing = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .eq(CartItemEntity::getProductId, request.getProductId()));
        if (existing != null) {
            existing.setQuantity(existing.getQuantity() + request.getQuantity());
            existing.setUpdatedAt(LocalDateTime.now());
            cartItemMapper.updateById(existing);
            return toResponse(existing, productService.requireEntity(existing.getProductId()));
        }

        CartItemEntity entity = new CartItemEntity();
        entity.setUserId(userId);
        entity.setProductId(request.getProductId());
        entity.setQuantity(request.getQuantity());
        entity.setSelected(Boolean.TRUE);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        cartItemMapper.insert(entity);
        return toResponse(entity, productService.requireEntity(entity.getProductId()));
    }

    @Override
    public List<CartItemResponse> listItems(String userId) {
        List<CartItemEntity> items = listEntities(userId);
        Map<Long, ProductEntity> productMap = items.stream()
                .map(item -> productService.requireEntity(item.getProductId()))
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity(), (left, right) -> left));
        return items.stream()
                .map(item -> toResponse(item, productMap.get(item.getProductId())))
                .toList();
    }

    @Override
    @Transactional
    public CartItemResponse updateItem(String userId, Long id, UpdateCartItemRequest request) {
        CartItemEntity entity = requireCartItem(userId, id);
        entity.setQuantity(request.getQuantity());
        entity.setUpdatedAt(LocalDateTime.now());
        cartItemMapper.updateById(entity);
        return toResponse(entity, productService.requireEntity(entity.getProductId()));
    }

    @Override
    @Transactional
    public void deleteItem(String userId, Long id) {
        requireCartItem(userId, id);
        cartItemMapper.deleteById(id);
    }

    @Override
    public List<CartItemEntity> listEntities(String userId) {
        return cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .orderByDesc(CartItemEntity::getUpdatedAt));
    }

    @Override
    @Transactional
    public void clearUserCart(String userId) {
        cartItemMapper.delete(new LambdaQueryWrapper<CartItemEntity>().eq(CartItemEntity::getUserId, userId));
    }

    private CartItemEntity requireCartItem(String userId, Long id) {
        CartItemEntity entity = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getId, id)
                .eq(CartItemEntity::getUserId, userId));
        if (entity == null) {
            throw new IllegalArgumentException("购物车条目不存在");
        }
        return entity;
    }

    private CartItemResponse toResponse(CartItemEntity item, ProductEntity product) {
        return CartItemResponse.builder()
                .id(item.getId())
                .productId(product.getId())
                .name(product.getName())
                .image(product.getImage())
                .price(product.getPrice())
                .quantity(item.getQuantity())
                .stock(product.getStock())
                .subtotal(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .build();
    }
}
