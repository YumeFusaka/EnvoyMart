package yumefusaka.envoymart.order.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.context.BaseContext;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.order.model.request.CheckoutRequest;
import yumefusaka.envoymart.order.model.response.LogisticsResponse;
import yumefusaka.envoymart.order.model.response.OrderResponse;
import yumefusaka.envoymart.order.service.OrderService;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/checkout")
    public Result<OrderResponse> checkout(@Valid @RequestBody CheckoutRequest request) {
        return Result.success(orderService.checkout(BaseContext.getCurrentId(), request));
    }

    @GetMapping
    public Result<List<OrderResponse>> list() {
        return Result.success(orderService.listOrders(BaseContext.getCurrentId()));
    }

    @GetMapping("/{id}")
    public Result<OrderResponse> detail(@PathVariable Long id) {
        return Result.success(orderService.getOrder(BaseContext.getCurrentId(), id));
    }

    @GetMapping("/{id}/logistics")
    public Result<LogisticsResponse> logistics(@PathVariable Long id) {
        return Result.success(orderService.getLogistics(BaseContext.getCurrentId(), id));
    }
}
