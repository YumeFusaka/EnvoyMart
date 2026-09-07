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
import yumefusaka.envoymart.common.util.Passwords;

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
                .eq(UserEntity::getUsername, request.getUsername()));

        // 口令不能进 SQL：BCrypt 每次哈希都带随机盐，等值查询永远匹配不上；
        // 而且明文比对会让「查得到用户」这件事本身成为可观测的旁路。
        boolean matched = user != null
                && Passwords.matches(request.getPassword(), user.getPassword());
        if (user == null) {
            // 用户不存在时也走一次等开销的比对，否则响应时间会泄漏用户名是否存在
            Passwords.wasteTimeLikeVerification(request.getPassword());
        }
        if (!matched) {
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
