package yumefusaka.envoymart.orderservice.service;

import yumefusaka.envoymart.orderservice.model.CheckoutRequest;
import yumefusaka.envoymart.orderservice.model.LogisticsResponse;
import yumefusaka.envoymart.orderservice.model.OrderResponse;

import java.util.List;

/**
 * 订单域。
 * <p>
 * 购物车不在这里 —— 它已拆到 {@link CartService}。两者是两个聚合：
 * 购物车可以长期存在、随时改动；订单一旦创建就进入只进不退的状态流转。
 */
public interface OrderDomainService {

    OrderResponse checkout(String userId, CheckoutRequest request);

    List<OrderResponse> listOrders(String userId);

    OrderResponse getOrder(String userId, Long orderId);

    LogisticsResponse getLogistics(String userId, Long orderId);

    /** 取消订单（高危操作，仅供已确认的调用方使用） */
    OrderResponse cancelOrder(String userId, Long orderId);

    /**
     * 支付完成回调 —— 把订单推进到已支付。
     * <p>
     * 入参只有 orderId：事件来自 MQ，没有调用方身份，也不该有（它表达的是"渠道收到了钱"，
     * 不是"某个用户在操作"）。幂等由状态条件更新保证，重复投递不会出问题。
     */
    void markPaid(Long orderId);
}
