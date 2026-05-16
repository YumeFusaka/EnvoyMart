package yumefusaka.envoymart.authservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.authservice.entity.UserEntity;
import yumefusaka.envoymart.authservice.mapper.UserMapper;
import yumefusaka.envoymart.authservice.model.LoginRequest;
import yumefusaka.envoymart.authservice.model.LoginResponse;
import yumefusaka.envoymart.authservice.model.UserProfile;
import yumefusaka.envoymart.authservice.service.AuthService;
import yumefusaka.envoymart.common.properties.JwtProperties;
import yumefusaka.envoymart.common.util.JwtUtils;

import java.util.Map;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final JwtProperties jwtProperties;

    public AuthServiceImpl(UserMapper userMapper, JwtProperties jwtProperties) {
        this.userMapper = userMapper;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, request.getUsername())
                .eq(UserEntity::getPassword, request.getPassword()));
        if (user == null) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return LoginResponse.builder()
                .token(JwtUtils.createToken(jwtProperties.getSecretKey(), jwtProperties.getTtl(), Map.of(
                        "id", user.getId(),
                        "username", user.getUsername()
                )))
                .user(UserProfile.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .nickname(user.getNickname())
                        .roleName(user.getRoleName())
                        .avatar(user.getAvatar())
                        .build())
                .build();
    }
}
