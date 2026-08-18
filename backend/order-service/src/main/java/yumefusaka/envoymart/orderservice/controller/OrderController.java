package yumefusaka.envoymart.orderservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.orderservice.model.AddCartItemRequest;
import yumefusaka.envoymart.orderservice.model.CartItemResponse;
import yumefusaka.envoymart.orderservice.model.CheckoutRequest;
import yumefusaka.envoymart.orderservice.model.LogisticsResponse;
import yumefusaka.envoymart.orderservice.model.OrderResponse;
import yumefusaka.envoymart.orderservice.model.UpdateCartItemRequest;
import yumefusaka.envoymart.orderservice.service.OrderDomainService;

import java.util.List;

@RestController
@RequestMapping
public class OrderController {

    private final OrderDomainService orderDomainService;

    public OrderController(OrderDomainService orderDomainService) {
        this.orderDomainService = orderDomainService;
    }

    @GetMapping("/cart")
    public Result<List<CartItemResponse>> listCart(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId) {
        return Result.success(orderDomainService.listCartItems(userId));
    }

    @PostMapping("/cart/items")
    public Result<CartItemResponse> addCart(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                            @Valid @RequestBody AddCartItemRequest request) {
        return Result.success(orderDomainService.addCartItem(userId, request));
    }

    @PutMapping("/cart/items/{id}")
    public Result<CartItemResponse> updateCart(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                               @PathVariable Long id,
                                               @Valid @RequestBody UpdateCartItemRequest request) {
        return Result.success(orderDomainService.updateCartItem(userId, id, request));
    }

    @PostMapping("/orders/checkout")
    public Result<OrderResponse> checkout(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                          @Valid @RequestBody CheckoutRequest request) {
        return Result.success(orderDomainService.checkout(userId, request));
    }

    @GetMapping("/orders")
    public Result<List<OrderResponse>> listOrders(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId) {
        return Result.success(orderDomainService.listOrders(userId));
    }

    @GetMapping("/orders/{id}")
    public Result<OrderResponse> detail(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                        @PathVariable Long id) {
        return Result.success(orderDomainService.getOrder(userId, id));
    }

    @GetMapping("/orders/{id}/logistics")
    public Result<LogisticsResponse> logistics(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                               @PathVariable Long id) {
        return Result.success(orderDomainService.getLogistics(userId, id));
    }
}
