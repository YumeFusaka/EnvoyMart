package yumefusaka.envoymart.orderservice.service;

import yumefusaka.envoymart.orderservice.model.AddCartItemRequest;
import yumefusaka.envoymart.orderservice.model.CartItemResponse;
import yumefusaka.envoymart.orderservice.model.UpdateCartItemRequest;

import java.util.List;

/**
 * 购物车。
 * <p>
 * 从订单域拆出来单独成服务：购物车与订单是两个聚合，状态机不同、生命周期不同 ——
 * 购物车可以长期存在且随时改动，订单一旦创建就进入只进不退的状态流转。
 * 混在一起还会让「下单要清空购物车」这类跨聚合操作看起来像内部调用。
 * <p>
 * 所有方法都要求 {@code userId}：购物车是用户私有数据，越权必须在服务层拦死。
 */
public interface CartService {

    List<CartItemResponse> list(String userId);

    CartItemResponse add(String userId, AddCartItemRequest request);

    CartItemResponse updateQuantity(String userId, Long id, UpdateCartItemRequest request);

    void remove(String userId, Long id);

    CartItemResponse setSelected(String userId, Long id, boolean selected);
}
