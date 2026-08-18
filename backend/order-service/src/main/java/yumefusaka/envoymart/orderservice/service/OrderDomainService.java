package yumefusaka.envoymart.orderservice.service;

import yumefusaka.envoymart.orderservice.model.AddCartItemRequest;
import yumefusaka.envoymart.orderservice.model.CartItemResponse;
import yumefusaka.envoymart.orderservice.model.CheckoutRequest;
import yumefusaka.envoymart.orderservice.model.LogisticsResponse;
import yumefusaka.envoymart.orderservice.model.OrderResponse;
import yumefusaka.envoymart.orderservice.model.UpdateCartItemRequest;

import java.util.List;

public interface OrderDomainService {

    CartItemResponse addCartItem(String userId, AddCartItemRequest request);

    List<CartItemResponse> listCartItems(String userId);

    CartItemResponse updateCartItem(String userId, Long id, UpdateCartItemRequest request);

    OrderResponse checkout(String userId, CheckoutRequest request);

    List<OrderResponse> listOrders(String userId);

    OrderResponse getOrder(String userId, Long orderId);

    LogisticsResponse getLogistics(String userId, Long orderId);
}
