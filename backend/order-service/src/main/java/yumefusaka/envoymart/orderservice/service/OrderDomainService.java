package yumefusaka.envoymart.orderservice.service;

import yumefusaka.envoymart.orderservice.model.CheckoutRequest;
import yumefusaka.envoymart.contract.LogisticsResponse;
import yumefusaka.envoymart.contract.OrderResponse;

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
     * 发货。**管理侧动作，没有调用方用户身份。**
     * <p>
     * 它同时创建履约单与首条物流轨迹 —— 轨迹是真实落库的数据，
     * 而不是按订单时间推算出来的。
     */
    OrderResponse shipOrder(Long orderId, String carrierCode, String carrierName, String trackingNo);

    /**
     * 确认收货。用户动作 —— 只有订单归属者能确认。
     * <p>
     * 这一步是售后与评价的**前置条件**：没有它，订单永远停在「已发货」，
     * 政策引擎要求的「已收货」不可达，整条售后链路走不通。
     */
    OrderResponse confirmReceipt(String userId, Long orderId);

    /**
     * 支付完成回调 —— 把订单推进到已支付。
     * <p>
     * 入参只有 orderId：事件来自 MQ，没有调用方身份，也不该有（它表达的是"渠道收到了钱"，
     * 不是"某个用户在操作"）。幂等由状态条件更新保证，重复投递不会出问题。
     */
    void markPaid(Long orderId);
}
