package yumefusaka.envoymart.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.auth.mapper.UserMapper;
import yumefusaka.envoymart.auth.model.entity.UserEntity;
import yumefusaka.envoymart.auth.model.request.LoginRequest;
import yumefusaka.envoymart.auth.model.response.LoginResponse;
import yumefusaka.envoymart.auth.model.response.UserProfile;
import yumefusaka.envoymart.auth.service.AuthService;
import yumefusaka.envoymart.common.properties.JwtProperties;
import yumefusaka.envoymart.utils.JwtUtils;

import java.util.HashMap;
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

        Map<String, Object> claims = new HashMap<>();
        claims.put("id", user.getId());
        claims.put("username", user.getUsername());

        return LoginResponse.builder()
                .token(JwtUtils.createToken(jwtProperties.getSecretKey(), jwtProperties.getTtl(), claims))
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
