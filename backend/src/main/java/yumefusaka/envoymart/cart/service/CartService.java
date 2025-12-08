package yumefusaka.envoymart.cart.service;

import yumefusaka.envoymart.cart.model.entity.CartItemEntity;
import yumefusaka.envoymart.cart.model.request.AddCartItemRequest;
import yumefusaka.envoymart.cart.model.request.UpdateCartItemRequest;
import yumefusaka.envoymart.cart.model.response.CartItemResponse;

import java.util.List;

public interface CartService {

    CartItemResponse addItem(String userId, AddCartItemRequest request);

    List<CartItemResponse> listItems(String userId);

    CartItemResponse updateItem(String userId, Long id, UpdateCartItemRequest request);

    void deleteItem(String userId, Long id);

    List<CartItemEntity> listEntities(String userId);

    void clearUserCart(String userId);
}
