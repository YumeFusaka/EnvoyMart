package yumefusaka.envoymart.authservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.authservice.entity.UserEntity;
import yumefusaka.envoymart.authservice.mapper.UserMapper;
import yumefusaka.envoymart.authservice.model.LoginRequest;
import yumefusaka.envoymart.authservice.model.LoginResponse;
import yumefusaka.envoymart.authservice.model.RegisterRequest;
import yumefusaka.envoymart.authservice.model.UserProfile;
import yumefusaka.envoymart.authservice.service.AuthService;
import yumefusaka.envoymart.common.properties.JwtProperties;
import yumefusaka.envoymart.common.util.JwtUtils;
import yumefusaka.envoymart.common.util.Passwords;
import yumefusaka.envoymart.common.util.Times;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {

    private static final int STATUS_ENABLED = 1;
    private static final String DEFAULT_ROLE = "USER";

    private final UserMapper userMapper;
    private final JwtProperties jwtProperties;

    public AuthServiceImpl(UserMapper userMapper, JwtProperties jwtProperties) {
        this.userMapper = userMapper;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public LoginResponse register(RegisterRequest request) {
        // 先查一次只是为了给出「用户名已被占用」这种友好提示。
        // 真正防重复的是数据库上的唯一索引——并发注册时两个请求会同时查不到，
        // 只有唯一索引拦得住，所以下面的 insert 必须捕获 DuplicateKeyException
        Long existing = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, request.getUsername()));
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("用户名已被占用");
        }

        LocalDateTime now = Times.now();
        UserEntity user = new UserEntity();
        // 32 位十六进制，正好填满 varchar(32) 主键
        user.setId(UUID.randomUUID().toString().replace("-", ""));
        user.setUsername(request.getUsername());
        user.setPassword(Passwords.hash(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setPhone(blankToNull(request.getPhone()));
        user.setRoleName(DEFAULT_ROLE);
        user.setStatus(STATUS_ENABLED);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 竞态：另一个请求刚刚插入了同名用户
            throw new IllegalArgumentException("用户名已被占用");
        }
        return issueToken(user);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, request.getUsername()));

        if (user == null) {
            // 用户不存在时也走一次等开销的比对，否则响应时间会泄漏用户名是否存在
            Passwords.wasteTimeLikeVerification(request.getPassword());
            throw new IllegalArgumentException("用户名或密码错误");
        }
        // 口令不能进 SQL：BCrypt 每次哈希都带随机盐，等值查询永远匹配不上；
        // 而且明文比对会让「查得到用户」这件事本身成为可观测的旁路。
        if (!Passwords.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        // 禁用检查放在口令校验**之后**：否则「这个账号存在但被禁用」会先于
        // 密码是否正确暴露出去，等于送给攻击者一个账号枚举接口
        if (user.getStatus() != null && user.getStatus() != STATUS_ENABLED) {
            throw new IllegalStateException("账号已被禁用，请联系客服");
        }
        return issueToken(user);
    }

    private LoginResponse issueToken(UserEntity user) {
        return LoginResponse.builder()
                .token(JwtUtils.createToken(jwtProperties.getSecretKey(), jwtProperties.getTtl(), Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        // 角色取自实体（注册时是刚写进库的那一行，登录时是查出来的那一行），
                        // 绝不取请求参数：客户端能决定的角色等于没有角色
                        JwtUtils.CLAIM_ROLE, user.getRoleName()
                )))
                .user(toProfile(user))
                .build();
    }

    private UserProfile toProfile(UserEntity user) {
        return UserProfile.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .roleName(user.getRoleName())
                .avatar(user.getAvatar())
                .phone(user.getPhone())
                .email(user.getEmail())
                .build();
    }

    /** 前端表单里未填写的选填项会传空串，统一转成 null——空串与「没填」在语义上不是一回事 */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
