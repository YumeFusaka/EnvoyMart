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
     * 发货 —— 管理侧动作，写在 {@code /internal/} 下。
     * <p>
     * <b>它不读身份、不校验订单归属</b>：谁调它、这单是谁的都无人检查，
     * 设计上唯一的一道门就是网关对 {@code /orders/internal/} 的一律 404。
     * 那道门曾经<b>并不存在</b>——这个路径长期不在 {@code JwtGatewayFilter} 的排除清单里，
     * 于是任何登录用户带自己的 Token 调它，就能把任意订单改成已发货并写入履约单与物流轨迹；
     * 而这里原先的注释写着「网关已屏蔽」，读代码的人只会去查网关、不会去数清单，
     * 洞就这样在有注释的地方留了下来。
     * <p>
     * 教训是<b>安全属性靠注释声明不住</b>，得靠机制：现在
     * {@code InternalEndpointCoverageTest} 会扫全部控制器，新增 {@code /internal} 接口
     * 却忘了登记就直接构建失败。要真正守住还差一层服务间凭证——见
     * {@code ProductController#deduct} 上的同类说明。
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
