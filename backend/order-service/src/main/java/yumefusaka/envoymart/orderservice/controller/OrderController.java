package yumefusaka.envoymart.orderservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.orderservice.model.CheckoutRequest;
import yumefusaka.envoymart.contract.LogisticsResponse;
import yumefusaka.envoymart.contract.OrderResponse;
import yumefusaka.envoymart.orderservice.service.OrderDomainService;

import java.util.List;

/**
 * 订单。
 * <p>
 * 购物车已拆到 {@link CartController}：它们是两个聚合，状态机与生命周期都不同。
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderDomainService orderDomainService;

    public OrderController(OrderDomainService orderDomainService) {
        this.orderDomainService = orderDomainService;
    }

    @PostMapping("/checkout")
    public Result<OrderResponse> checkout(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @Valid @RequestBody CheckoutRequest request) {
        return Result.success(orderDomainService.checkout(userId, request));
    }

    @GetMapping
    public Result<List<OrderResponse>> listOrders(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId) {
        return Result.success(orderDomainService.listOrders(userId));
    }

    @GetMapping("/{id}")
    public Result<OrderResponse> detail(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        return Result.success(orderDomainService.getOrder(userId, id));
    }

    @PostMapping("/{id}/cancel")
    public Result<OrderResponse> cancelOrder(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        return Result.success(orderDomainService.cancelOrder(userId, id));
    }

    /**
     * 确认收货。用户动作 —— 它是售后与评价的前置条件：
     * 没有这一步，订单永远停在「已发货」，政策引擎要求的「已收货」不可达。
     */
    @PostMapping("/{id}/receive")
    public Result<OrderResponse> confirmReceipt(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        return Result.success(orderDomainService.confirmReceipt(userId, id));
    }

    /**
     * 发货 —— 管理侧动作，网关已屏蔽，只有服务间或运维直连可达。
     * <p>
     * 它同时创建履约单与首条物流轨迹，轨迹因此是**真实落库的数据**。
     */
    @PostMapping("/internal/{id}/ship")
    public Result<OrderResponse> ship(
            @PathVariable("id") Long id,
            @RequestParam("carrierCode") String carrierCode,
            @RequestParam("carrierName") String carrierName,
            @RequestParam("trackingNo") String trackingNo) {
        return Result.success(orderDomainService.shipOrder(id, carrierCode, carrierName, trackingNo));
    }

    @GetMapping("/{id}/logistics")
    public Result<LogisticsResponse> logistics(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        return Result.success(orderDomainService.getLogistics(userId, id));
    }
}
