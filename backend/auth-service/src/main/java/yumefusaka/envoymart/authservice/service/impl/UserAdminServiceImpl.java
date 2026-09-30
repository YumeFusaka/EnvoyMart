package yumefusaka.envoymart.authservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.authservice.entity.UserAddressEntity;
import yumefusaka.envoymart.authservice.entity.UserEntity;
import yumefusaka.envoymart.authservice.mapper.UserAddressMapper;
import yumefusaka.envoymart.authservice.mapper.UserMapper;
import yumefusaka.envoymart.authservice.model.UserRoles;
import yumefusaka.envoymart.authservice.model.admin.AdminUserDetail;
import yumefusaka.envoymart.authservice.model.admin.AdminUserQuery;
import yumefusaka.envoymart.authservice.model.admin.AdminUserSummary;
import yumefusaka.envoymart.authservice.service.UserAdminService;
import yumefusaka.envoymart.authservice.state.AuthStateStore;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.util.Times;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class UserAdminServiceImpl implements UserAdminService {

    private final UserMapper userMapper;
    private final UserAddressMapper userAddressMapper;
    private final AuthStateStore authStateStore;

    public UserAdminServiceImpl(UserMapper userMapper,
                                UserAddressMapper userAddressMapper,
                                AuthStateStore authStateStore) {
        this.userMapper = userMapper;
        this.userAddressMapper = userAddressMapper;
        this.authStateStore = authStateStore;
    }

    @Override
    public PageResult<AdminUserSummary> list(AdminUserQuery query) {
        Page<UserEntity> page = new Page<>(query.mpCurrent(), query.safeSize());
        Page<UserEntity> result = userMapper.selectPage(page, adminWrapper(query));

        List<AdminUserSummary> records = result.getRecords().stream()
                .map(this::toSummary)
                .toList();

        return PageResult.<AdminUserSummary>builder()
                .records(records)
                .total(result.getTotal())
                .page(query.zeroBasedPage())
                .size(query.safeSize())
                .build();
    }

    @Override
    public AdminUserDetail detail(String userId) {
        UserEntity user = requireUser(userId);
        // 只数条数，不取地址内容：收货人姓名与门牌号是用户隐私，
        // 运营要看的是「有没有填过」，不是「填到哪」
        Long addressCount = userAddressMapper.selectCount(new LambdaQueryWrapper<UserAddressEntity>()
                .eq(UserAddressEntity::getUserId, userId));
        return new AdminUserDetail(toSummary(user), addressCount == null ? 0L : addressCount);
    }

    @Override
    @Transactional
    public AdminUserSummary changeStatus(String userId, Integer status, String reason, String operatorId) {
        UserEntity user = requireUser(userId);
        rejectSelfOperation(userId, operatorId, "状态");

        // 取值先校验，再判幂等：否则「已经是禁用态的用户」上重复提交一次不带原因的禁用请求
        // 会被幂等分支放行，而这个请求单独看是不合法的
        if (status == null || (status != UserEntity.STATUS_ENABLED && status != UserEntity.STATUS_DISABLED)) {
            throw new IllegalArgumentException("不支持的用户状态：" + status);
        }
        String trimmedReason = reason == null ? "" : reason.trim();
        if (status == UserEntity.STATUS_DISABLED && trimmedReason.isEmpty()) {
            throw new IllegalArgumentException("禁用用户必须填写原因");
        }

        if (status == user.getStatus()) {
            // 重复点击是常态，不该产生副作用：刷新留痕会让这条记录最终指向最后一个手滑的人，
            // 而不是真正做决定的那次。这与评价隐藏的幂等是同一条规矩
            log.info("用户状态未变化，跳过: userId={}, status={}, operator={}", userId, status, operatorId);
            return toSummary(user);
        }

        LambdaUpdateWrapper<UserEntity> update = new LambdaUpdateWrapper<UserEntity>()
                .eq(UserEntity::getId, userId)
                .set(UserEntity::getStatus, status)
                .set(UserEntity::getUpdatedAt, Times.now());
        if (status == UserEntity.STATUS_DISABLED) {
            update.set(UserEntity::getDisabledReason, trimmedReason)
                    .set(UserEntity::getDisabledBy, operatorId)
                    .set(UserEntity::getDisabledAt, Times.now());
        } else {
            // 恢复时三列一起清空，而不是留着："禁用" 与 "有禁用原因" 必须是同一件事的两个说法。
            // 留着一条已启用账号的禁用原因，会让「按原因排查」查出已经被撤销的操作
            update.set(UserEntity::getDisabledReason, null)
                    .set(UserEntity::getDisabledBy, null)
                    .set(UserEntity::getDisabledAt, null);
        }
        userMapper.update(null, update);

        // 关键的一步：把新状态发布给网关。放在事务内是刻意的——
        // 写失败会连数据库改动一起回滚，"禁用了个寂寞" 比 "禁用没生效并报错" 危险得多
        authStateStore.publish(userId, status, user.getRoleName());

        log.info("用户状态变更: userId={}, username={}, {} -> {}, operator={}, reason={}",
                userId, user.getUsername(), user.getStatus(), status, operatorId,
                status == UserEntity.STATUS_DISABLED ? trimmedReason : null);

        return toSummary(userMapper.selectById(userId));
    }

    @Override
    @Transactional
    public AdminUserSummary changeRole(String userId, String role, String operatorId) {
        UserEntity user = requireUser(userId);
        rejectSelfOperation(userId, operatorId, "角色");

        String target = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!UserRoles.ASSIGNABLE.contains(target)) {
            // 角色字符串会进 JWT 的 claim、由网关读出来判权限。放进一个没人认得的值不会当场报错，
            // 只会让这个人静默地什么也管不了（或者反过来，拿到不该有的权限）
            throw new IllegalArgumentException("不支持的角色：" + role);
        }

        if (target.equals(user.getRoleName())) {
            log.info("用户角色未变化，跳过: userId={}, role={}, operator={}", userId, target, operatorId);
            return toSummary(user);
        }

        userMapper.update(null, new LambdaUpdateWrapper<UserEntity>()
                .eq(UserEntity::getId, userId)
                .set(UserEntity::getRoleName, target)
                .set(UserEntity::getUpdatedAt, Times.now()));
        // 提权与降权都必须立刻对存量 Token 生效，理由同 changeStatus
        authStateStore.publish(userId, user.getStatus(), target);

        log.info("用户角色变更: userId={}, username={}, {} -> {}, operator={}",
                userId, user.getUsername(), user.getRoleName(), target, operatorId);

        return toSummary(userMapper.selectById(userId));
    }

    private LambdaQueryWrapper<UserEntity> adminWrapper(AdminUserQuery query) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();

        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            String kw = query.getKeyword().trim();
            // 四个字段之间是「或」，整体与其它条件是「与」：不裹这一层 and，
            // 后面任何一个 eq 都会被 or 短路掉，筛选条件形同虚设
            wrapper.and(w -> w.like(UserEntity::getUsername, kw)
                    .or().like(UserEntity::getNickname, kw)
                    .or().like(UserEntity::getPhone, kw)
                    .or().like(UserEntity::getEmail, kw));
        }

        if (query.getStatus() != null) {
            if (query.getStatus() != UserEntity.STATUS_ENABLED
                    && query.getStatus() != UserEntity.STATUS_DISABLED) {
                // 静默忽略会让页面展示「全部用户」而看的人以为自己筛的是禁用用户
                throw new IllegalArgumentException("不支持的用户状态：" + query.getStatus());
            }
            wrapper.eq(UserEntity::getStatus, query.getStatus());
        }

        List<String> roles = query.roleList();
        if (!roles.isEmpty()) {
            wrapper.in(UserEntity::getRoleName, roles);
        }

        // 排序带唯一兜底列：只按 created_at 排时，同一秒注册的两个用户翻页会漏一个、重一个
        return wrapper.orderByDesc(UserEntity::getCreatedAt).orderByDesc(UserEntity::getId);
    }

    /**
     * 拒绝「管理员对自己动手」。
     * <p>
     * 这不是权限不足——他是个管理员，网关放行了。这是<b>操作本身没有回头路</b>：
     * 禁用自己之后没法再登录，降权自己之后没有了管理权限，两种情况下撤销操作所需的权限
     * 恰好就是刚刚丢掉的那一个。这类操作只能由另一个管理员来做，而「另一个管理员」
     * 是否存在是外部事实，服务层不该假设。
     */
    private void rejectSelfOperation(String userId, String operatorId, String what) {
        if (userId.equals(operatorId)) {
            throw new IllegalStateException("不能修改自己的" + what + "：改动一旦生效，你自己无法撤销它");
        }
    }

    /** 写成方法引用会在泛型推断上打架（selectById 的入参是 Serializable），就直白点 */
    private UserEntity requireUser(String userId) {
        UserEntity user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return user;
    }

    /**
     * 实体 → 管理端视图。
     * <p>
     * <b>不返回实体本身</b>：{@code UserEntity} 带着 {@code password}，只要有一个接口顺手
     * 返回它，口令哈希就跟着响应体出去了。这里的字段是逐个挑的，挑漏的反面是「少显示一个字段」，
     * 而不是「多泄漏一个」。
     */
    private AdminUserSummary toSummary(UserEntity entity) {
        return AdminUserSummary.builder()
                .id(entity.getId())
                .username(entity.getUsername())
                .nickname(entity.getNickname())
                .avatar(entity.getAvatar())
                .phone(entity.getPhone())
                .email(entity.getEmail())
                .roleName(entity.getRoleName())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .disabledReason(entity.getDisabledReason())
                .disabledBy(entity.getDisabledBy())
                .disabledAt(entity.getDisabledAt())
                .build();
    }
}
