package yumefusaka.envoymart.authservice.service;

import yumefusaka.envoymart.authservice.entity.UserAddressEntity;
import yumefusaka.envoymart.authservice.model.AddressRequest;

import java.util.List;

/**
 * 收货地址簿。
 * <p>
 * 所有方法都要求传入 {@code userId}：地址是用户的私有数据，越权必须在服务层拦死，
 * 不能寄希望于「调用方会传对」。
 */
public interface AddressService {

    List<UserAddressEntity> list(String userId);

    UserAddressEntity get(Long id, String userId);

    UserAddressEntity create(String userId, AddressRequest request);

    UserAddressEntity update(Long id, String userId, AddressRequest request);

    void delete(Long id, String userId);

    void setDefault(Long id, String userId);
}
