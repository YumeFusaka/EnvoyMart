package yumefusaka.envoymart.order.service;

import yumefusaka.envoymart.order.model.request.CheckoutRequest;
import yumefusaka.envoymart.order.model.response.LogisticsResponse;
import yumefusaka.envoymart.order.model.response.OrderResponse;

import java.util.List;

public interface OrderService {

    OrderResponse checkout(String userId, CheckoutRequest request);

    List<OrderResponse> listOrders(String userId);

    OrderResponse getOrder(String userId, Long orderId);

    LogisticsResponse getLogistics(String userId, Long orderId);

    OrderResponse getLatestOrder(String userId);
}
