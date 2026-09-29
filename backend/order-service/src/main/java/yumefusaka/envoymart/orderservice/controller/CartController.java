package yumefusaka.envoymart.orderservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import yumefusaka.envoymart.orderservice.model.SelectCartItemRequest;
import yumefusaka.envoymart.orderservice.model.UpdateCartItemRequest;
import yumefusaka.envoymart.orderservice.service.CartService;

import java.util.List;

/**
 * 购物车。
 * <p>
 * 用户身份取自网关放进请求头的 {@code X-User-Id}，不从参数里取 ——
 * 后者等于「传谁的 id 就能改谁的购物车」。
 */
@RestController
@RequestMapping("/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public Result<List<CartItemResponse>> list(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId) {
        return Result.success(cartService.list(userId));
    }

    @PostMapping("/items")
    public Result<CartItemResponse> add(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @Valid @RequestBody AddCartItemRequest request) {
        return Result.success(cartService.add(userId, request));
    }

    @PutMapping("/items/{id}")
    public Result<CartItemResponse> update(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateCartItemRequest request) {
        return Result.success(cartService.updateQuantity(userId, id, request));
    }

    @DeleteMapping("/items/{id}")
    public Result<Void> remove(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        cartService.remove(userId, id);
        return Result.success(null);
    }

    /**
     * 批量勾选 / 取消（「全选」走它）。
     * <p>
     * 路径刻意**不放在 {@code /items/} 下面**：那个前缀下有 {@code /items/{id}}，
     * 而实测 {@code PUT /cart/items/selected} 会被它抢走匹配、然后因为
     * 「selected 转不成 Long」报 400 —— 报错信息是「参数格式不正确：id」，
     * 与真实原因（路径歧义）毫无关系，很难往那个方向想。
     */
    @PutMapping("/selection")
    public Result<List<CartItemResponse>> setAllSelected(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @Valid @RequestBody SelectCartItemRequest request) {
        return Result.success(cartService.setAllSelected(userId, request.getSelected()));
    }

    @PutMapping("/items/{id}/selected")
    public Result<CartItemResponse> setSelected(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id,
            @Valid @RequestBody SelectCartItemRequest request) {
        return Result.success(cartService.setSelected(userId, id, request.getSelected()));
    }
}
