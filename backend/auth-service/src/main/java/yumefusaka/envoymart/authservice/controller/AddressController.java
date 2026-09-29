package yumefusaka.envoymart.authservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.authservice.entity.UserAddressEntity;
import yumefusaka.envoymart.authservice.model.AddressRequest;
import yumefusaka.envoymart.authservice.service.AddressService;
import yumefusaka.envoymart.common.context.BaseContext;
import yumefusaka.envoymart.common.result.Result;

import java.util.List;

/**
 * 收货地址簿。
 * <p>
 * 用户身份取自 {@link BaseContext}（由网关注入的 {@code X-User-Id} 落地到这里），
 * <b>绝不从请求参数里取</b>——那样调用方传谁的 id 就能改谁的地址。
 */
@RestController
@RequestMapping("/auth/addresses")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    public Result<List<UserAddressEntity>> list() {
        return Result.success(addressService.list(BaseContext.getCurrentId()));
    }

    @GetMapping("/{id}")
    public Result<UserAddressEntity> get(@PathVariable Long id) {
        return Result.success(addressService.get(id, BaseContext.getCurrentId()));
    }

    @PostMapping
    public Result<UserAddressEntity> create(@Valid @RequestBody AddressRequest request) {
        return Result.success(addressService.create(BaseContext.getCurrentId(), request));
    }

    @PutMapping("/{id}")
    public Result<UserAddressEntity> update(@PathVariable Long id,
                                            @Valid @RequestBody AddressRequest request) {
        return Result.success(addressService.update(id, BaseContext.getCurrentId(), request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        addressService.delete(id, BaseContext.getCurrentId());
        return Result.success(null);
    }

    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        addressService.setDefault(id, BaseContext.getCurrentId());
        return Result.success(null);
    }
}
