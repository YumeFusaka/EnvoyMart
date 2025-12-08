package yumefusaka.envoymart.cart.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.cart.model.request.AddCartItemRequest;
import yumefusaka.envoymart.cart.model.request.UpdateCartItemRequest;
import yumefusaka.envoymart.cart.model.response.CartItemResponse;
import yumefusaka.envoymart.cart.service.CartService;
import yumefusaka.envoymart.common.context.BaseContext;
import yumefusaka.envoymart.common.result.Result;

import java.util.List;

@RestController
@RequestMapping("/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public Result<List<CartItemResponse>> list() {
        return Result.success(cartService.listItems(BaseContext.getCurrentId()));
    }

    @PostMapping("/items")
    public Result<CartItemResponse> addItem(@Valid @RequestBody AddCartItemRequest request) {
        return Result.success(cartService.addItem(BaseContext.getCurrentId(), request));
    }

    @PutMapping("/items/{id}")
    public Result<CartItemResponse> updateItem(@PathVariable Long id, @Valid @RequestBody UpdateCartItemRequest request) {
        return Result.success(cartService.updateItem(BaseContext.getCurrentId(), id, request));
    }

    @DeleteMapping("/items/{id}")
    public Result<Void> deleteItem(@PathVariable Long id) {
        cartService.deleteItem(BaseContext.getCurrentId(), id);
        return Result.success();
    }
}
