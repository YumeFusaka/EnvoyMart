package yumefusaka.envoymart.authservice.state;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.authservice.entity.UserEntity;
import yumefusaka.envoymart.authservice.mapper.UserMapper;
import yumefusaka.envoymart.authservice.model.UserRoles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时把「非正常状态」的用户补进 {@link AuthStateStore}。
 * <p>
 * 补两类：<b>被禁用的</b>，以及<b>角色不是默认值的管理员</b>。第二类看起来多余，
 * 但它正是「Redis 被清空之后」最容易出错的地方——管理员的提权只改数据库和 Redis，
 * 而他手里的 Token 仍然是 USER；Redis 一空，网关就只能按 Token 判断，
 * 于是一个管理员会静默地失去管理权限，且没有任何地方报错。
 * <p>
 * 这里查全表但只挑出这两类，不做分页：故意如此。用户表在演示项目里是几十行，
 * 而「分页同步」会引入「同步到一半失败」的中间态——那比多查几百行难处理得多。
 * 真到百万用户的量级，这里要改成按更新时间增量同步，届时再说。
 */
@Component
public class AuthStateInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthStateInitializer.class);

    private static final int STATUS_ENABLED = 1;

    private final UserMapper userMapper;
    private final AuthStateStore authStateStore;

    public AuthStateInitializer(UserMapper userMapper, AuthStateStore authStateStore) {
        this.userMapper = userMapper;
        this.authStateStore = authStateStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<UserEntity> abnormal;
        try {
            abnormal = userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                    .ne(UserEntity::getStatus, STATUS_ENABLED)
                    .or()
                    .ne(UserEntity::getRoleName, UserRoles.USER));
        } catch (Exception e) {
            log.error("[AuthState] 启动同步跳过：读取用户表失败", e);
            return;
        }

        Map<String, String> states = new LinkedHashMap<>();
        for (UserEntity user : abnormal) {
            int status = user.getStatus() == null ? STATUS_ENABLED : user.getStatus();
            states.put(user.getId(), status + "|" + user.getRoleName());
        }
        authStateStore.syncFromDatabase(states);
    }
}
