package yumefusaka.envoymart.authservice.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.authservice.entity.UserEntity;
import yumefusaka.envoymart.authservice.model.UserRoles;
import yumefusaka.envoymart.common.web.AuthState;

import java.util.Map;

/**
 * 认证态的跨服务共享存储 —— 回答「这个 Token 现在还作数吗」。
 * <p>
 * <b>要解决的问题</b>：JWT 是自证的，签发之后服务端就再也管不着它了。于是「禁用用户」
 * 与「调整角色」这两个管理动作只改了数据库，而对方手里的 Token <b>还能继续用到过期</b>
 * （本项目 TTL 是 7 天）。那等于没禁用——一个被禁用的账号仍然能下单、能看自己的订单，
 * 而且界面上一切正常。
 * <p>
 * <b>存什么</b>：只有<b>被改动过</b>的用户才在这个哈希里（field = userId，
 * value = {@code status|role}）。没被改动过的用户查不到，网关就按 Token 里的角色放行。
 * 这个「只存异常」的形状是有意的：
 * <ul>
 *   <li>正常用户零额外存储，也不需要预热全量用户；</li>
 *   <li>Redis 被清空后，唯一丢失的是「已禁用用户仍然被拒」这一条——而它会被
 *       {@link #syncFromDatabase} 在下次启动时补回来，登录链路也始终查库；</li>
 *   <li>「查不到就放行」让 Redis 故障时退化成「回到只有 JWT 的旧行为」，
 *       而不是「全站用户突然都登不上」。</li>
 * </ul>
 * <p>
 * <b>写失败必须抛出去</b>：这是与读相反的一侧。读失败是可用性问题，降级放行；
 * 写失败意味着「管理员以为禁用生效了、实际没有」，那是一条会一直裂着没人知道的口子。
 * 所以调用方（{@code UserAdminServiceImpl}）让异常冒泡，整个管理操作回滚 ——
 * <b>宁可禁用不了并报错，也不要禁用了个寂寞</b>。
 */
@Component
public class AuthStateStore {

    private static final Logger log = LoggerFactory.getLogger(AuthStateStore.class);

    private final StringRedisTemplate redisTemplate;

    public AuthStateStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 发布某个用户的当前认证态。禁用、启用、改角色都要调它。
     * <p>
     * 不做异常兜底：Redis 写不进去时抛出，由调用方的事务回滚数据库改动。
     */
    public void publish(String userId, int status, String role) {
        redisTemplate.opsForHash().put(AuthState.KEY, userId, AuthState.format(status, role));
        log.info("[AuthState] 已发布认证态 userId={} status={} role={}", userId, status, role);
    }

    /**
     * 把 Redis 里的认证态对齐到「刚刚查库得到的真相」。登录成功后调一次。
     * <p>
     * 这是一个便宜的自愈点：登录成功本身就是「库里这个账号是启用状态」的证明，
     * 顺手把陈旧条目纠回来。要纠的场景很窄——管理操作里 Redis 写成功、而事务最后提交失败，
     * 于是 Redis 记着「已禁用」而库里其实没变。此时那个用户会在下次登录成功后立刻恢复正常，
     * 而不是一直被判为禁用直到服务重启。
     * <p>
     * 失败只记警告：登录是主链路，不能因为 Redis 抖一下就登不上——那正是本类
     * 「读侧降级」要避免的事情。
     */
    public void reconcileFromLogin(String userId, int status, String role) {
        try {
            if (isNormal(status, role)) {
                // 正常态不该留在哈希里。用 remove 而不是 put("1|USER")：
                // 让「查不到就放行」这条降级路径继续覆盖绝大多数用户
                if (Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(AuthState.KEY, userId))) {
                    redisTemplate.opsForHash().delete(AuthState.KEY, userId);
                    log.info("[AuthState] 登录纠正：清除陈旧的认证态 userId={}", userId);
                }
            } else {
                publish(userId, status, role);
            }
        } catch (Exception e) {
            log.warn("[AuthState] 登录纠正失败（不影响本次登录）userId={}", userId, e);
        }
    }

    /** 「正常态」的定义：启用且默认角色。与网关「查不到就按 Token 放行」是同一件事的两面 */
    public static boolean isNormal(int status, String role) {
        return status == UserEntity.STATUS_ENABLED && UserRoles.USER.equals(role);
    }

    /**
     * 启动时把库里「非正常」的用户补进 Redis。
     * <p>
     * 覆盖的场景：Redis 被清空、首次部署、或者上一次禁用时 Redis 正好写失败。
     * 不做全量同步——正常情况下在库的绝大多数用户都是 {status=1, 原角色}，
     * 灌进去既没必要（网关查不到就按 Token 放行）又会让这个哈希无界增长。
     * <p>
     * 失败只记 ERROR 不让启动失败：登录链路本来就查库，存量 Token 的失效判断
     * 晚一点补上，比整个服务起不来要好。
     */
    public void syncFromDatabase(Map<String, String> abnormalUsers) {
        if (abnormalUsers.isEmpty()) {
            log.info("[AuthState] 无需同步：库里没有被禁用或非默认角色的用户");
            return;
        }
        try {
            redisTemplate.opsForHash().putAll(AuthState.KEY, abnormalUsers);
            log.info("[AuthState] 启动同步完成，共 {} 个非正常状态的用户", abnormalUsers.size());
        } catch (Exception e) {
            log.error("[AuthState] 启动同步失败：已禁用用户手中的旧 Token 在本次运行期内仍会被放行，"
                    + "需要人工确认 Redis 可用后重启本服务", e);
        }
    }
}
