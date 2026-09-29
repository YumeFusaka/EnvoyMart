package yumefusaka.envoymart.authservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.authservice.entity.UserAddressEntity;
import yumefusaka.envoymart.authservice.mapper.UserAddressMapper;
import yumefusaka.envoymart.authservice.model.AddressRequest;
import yumefusaka.envoymart.authservice.service.AddressService;
import yumefusaka.envoymart.common.util.Times;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AddressServiceImpl implements AddressService {

    /** 上限不是为了省存储，而是防止脚本刷出一屏地址把下单页的地址选择撑爆 */
    private static final int MAX_ADDRESSES = 20;
    private static final int DEFAULT_YES = 1;
    private static final int DEFAULT_NO = 0;

    private final UserAddressMapper addressMapper;

    public AddressServiceImpl(UserAddressMapper addressMapper) {
        this.addressMapper = addressMapper;
    }

    @Override
    public List<UserAddressEntity> list(String userId) {
        return addressMapper.selectList(new LambdaQueryWrapper<UserAddressEntity>()
                .eq(UserAddressEntity::getUserId, userId)
                // 默认地址排最前，其余按新加入的在前
                .orderByDesc(UserAddressEntity::getIsDefault)
                .orderByDesc(UserAddressEntity::getId));
    }

    @Override
    public UserAddressEntity get(Long id, String userId) {
        return requireOwned(id, userId);
    }

    @Override
    @Transactional
    public UserAddressEntity create(String userId, AddressRequest request) {
        Long count = addressMapper.selectCount(new LambdaQueryWrapper<UserAddressEntity>()
                .eq(UserAddressEntity::getUserId, userId));
        if (count != null && count >= MAX_ADDRESSES) {
            throw new IllegalStateException("收货地址最多 " + MAX_ADDRESSES + " 条");
        }

        // 第一条地址自动成为默认。否则用户加完地址还要再点一次「设为默认」，
        // 而首次下单时页面上一条默认地址都没有
        boolean asDefault = count == null || count == 0 || Boolean.TRUE.equals(request.getIsDefault());

        LocalDateTime now = Times.now();
        UserAddressEntity address = new UserAddressEntity();
        address.setUserId(userId);
        applyRequest(address, request);
        address.setCreatedAt(now);
        address.setUpdatedAt(now);

        if (asDefault) {
            clearDefault(userId);
            address.setIsDefault(DEFAULT_YES);
        } else {
            address.setIsDefault(DEFAULT_NO);
        }
        addressMapper.insert(address);
        return address;
    }

    @Override
    @Transactional
    public UserAddressEntity update(Long id, String userId, AddressRequest request) {
        UserAddressEntity address = requireOwned(id, userId);
        applyRequest(address, request);
        address.setUpdatedAt(Times.now());

        if (Boolean.TRUE.equals(request.getIsDefault()) && !isDefaultFlag(address.getIsDefault())) {
            clearDefault(userId);
            address.setIsDefault(DEFAULT_YES);
        } else if (Boolean.FALSE.equals(request.getIsDefault()) && isDefaultFlag(address.getIsDefault())) {
            // 允许取消默认而不自动指定下一条：用户接下来自己去设一条即可，
            // 自动挑一条会让「我明明取消了默认」变得莫名其妙
            address.setIsDefault(DEFAULT_NO);
        }
        addressMapper.updateById(address);
        return address;
    }

    @Override
    @Transactional
    public void delete(Long id, String userId) {
        UserAddressEntity address = requireOwned(id, userId);
        addressMapper.deleteById(id);

        // 删掉的是默认地址时，把最早加入的一条顶上。
        // 不补的话用户会停在「有地址但没有默认」的状态，下单页默认选不中任何一条
        if (isDefaultFlag(address.getIsDefault())) {
            UserAddressEntity fallback = addressMapper.selectOne(
                    new LambdaQueryWrapper<UserAddressEntity>()
                            .eq(UserAddressEntity::getUserId, userId)
                            .orderByAsc(UserAddressEntity::getId)
                            .last("limit 1"));
            if (fallback != null) {
                fallback.setIsDefault(DEFAULT_YES);
                fallback.setUpdatedAt(Times.now());
                addressMapper.updateById(fallback);
            }
        }
    }

    @Override
    @Transactional
    public void setDefault(Long id, String userId) {
        requireOwned(id, userId);
        // 先清后置，两步在同一事务内：中间态不可见，也就不会出现两条默认地址
        clearDefault(userId);

        UserAddressEntity patch = new UserAddressEntity();
        patch.setId(id);
        patch.setIsDefault(DEFAULT_YES);
        patch.setUpdatedAt(Times.now());
        addressMapper.updateById(patch);
    }

    /**
     * 取出并校验归属。
     * <p>
     * 「地址不存在」与「地址不属于你」返回同一句话：区分开来就等于告诉调用方
     * 哪些 ID 是有效的，越权探测就此变成一次盲注。
     */
    private UserAddressEntity requireOwned(Long id, String userId) {
        UserAddressEntity address = id == null ? null : addressMapper.selectById(id);
        if (address == null || !address.getUserId().equals(userId)) {
            throw new IllegalArgumentException("收货地址不存在");
        }
        return address;
    }

    private void clearDefault(String userId) {
        addressMapper.update(null, new LambdaUpdateWrapper<UserAddressEntity>()
                .eq(UserAddressEntity::getUserId, userId)
                .eq(UserAddressEntity::getIsDefault, DEFAULT_YES)
                .set(UserAddressEntity::getIsDefault, DEFAULT_NO));
    }

    private boolean isDefaultFlag(Integer value) {
        // 先判 null 再拆箱，避免历史数据里 is_default 为空时 NPE
        return value != null && value == DEFAULT_YES;
    }

    private void applyRequest(UserAddressEntity address, AddressRequest request) {
        address.setReceiverName(request.getReceiverName());
        address.setReceiverPhone(request.getReceiverPhone());
        address.setProvince(request.getProvince());
        address.setCity(request.getCity());
        address.setDistrict(request.getDistrict());
        address.setDetail(request.getDetail());
        address.setTag(request.getTag() == null || request.getTag().isBlank() ? null : request.getTag());
    }
}
