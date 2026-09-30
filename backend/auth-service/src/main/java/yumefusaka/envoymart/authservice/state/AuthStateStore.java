package yumefusaka.envoymart.authservice.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
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
 * <b>存什么</b>：只有<b>被管理操作改动过</b>的用户才在这个哈希里（field = userId，
 * value = {@code status|role}）。没被改动过的用户查不到，网关就按 Token 里的角色放行。
 * 这个「只存被改动过的人」的形状是有意的：
 * <ul>
 *   <li>正常用户零额外存储，也不需要预热全量用户；</li>
 *   <li>记录一旦写下就是权威的——它同时覆盖「禁用未生效」与「降权被旧 Token 绕过」两类问题，
 *       所以 {@link #reconcileFromLogin} 不会因为「库里看着是正常的」就把它删掉（见那里的说明）；</li>
 *   <li>Redis 被清空后，丢失的是「被改动过的用户按新身份判权」这一条——而它会被
 *       {@link #syncFromDatabase} 在下次启动时补回来（只覆盖禁用与非常规角色，
 *       降权成 USER 的人补不回来，那需要人工重新处置）；</li>
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
     * 这是一个便宜的自愈点，要纠的场景很窄——管理操作里 Redis 写成功、而事务最后提交失败，
     * 于是 Redis 记着「已禁用/旧角色」而库里其实没变。此时那个用户会在下次登录成功后恢复正常，
     * 而不是一直被判错到服务重启。
     * <p>
     * <b>只有「记录与库不一致」才改写，一致的记录一律原样留着。</b>这一点是被一次真实漏洞
     * 逼出来的（曾经这里对「启用 + USER」直接删记录）：
     * <ul>
     *   <li>删除的语义是「这个用户从没被管理操作碰过，Token 里的角色就是当前的」——
     *       网关查不到记录时会退回 Token 里的 {@code role} 快照（见 {@code JwtGatewayFilter}）。</li>
     *   <li>但「被降权成 USER」的库状态，与「从没被碰过」的库状态<b>一模一样</b>。
     *       于是被降级的人只要自己重新登录一次（不需要任何特权），就把降权留下的
     *       {@code 1|USER} 擦掉，手里那张还没过期的 ADMIN Token 立刻重新拿到全部管理权限——
     *       撤销被静默回滚，而且网关的回落分支不写日志，事后查不出来。</li>
     *   <li>记录因此是「这个用户被改动过」的唯一凭证，不能靠比对库状态推断出来，
     *       只能留在原地。</li>
     * </ul>
     * 禁用不走这条路径：被禁用的账号登录时在上游就被挡住了，根本到不了这里。
     * <p>
     * 失败只记警告：登录是主链路，不能因为 Redis 抖一下就登不上——那正是本类
     * 「读侧降级」要避免的事情。
     * <p>
     * ponytail: 记录只增不减，规模 = 被管理操作碰过的用户数。要回收得给值加上改写时间，
     * 清扫掉超过 JWT TTL 的条目（那时它们护着的旧 Token 都已过期）；量级上去再做。
     */
    public void reconcileFromLogin(String userId, int status, String role) {
        try {
            Object raw = redisTemplate.opsForHash().get(AuthState.KEY, userId);
            if (raw == null) {
                // 没有记录 = 这个用户从没被管理操作碰过，Token 里的角色就是当前的。
                // 什么都不写：绝大多数用户走这条路径，哈希里只有被改动过的人才占位
                return;
            }
            String current = raw.toString();
            if (current.equals(AuthState.format(status, role))) {
                return;
            }
            // 不一致 = 陈旧条目（Redis 写成功而事务回滚）。以库为准覆盖，
            // 而不是删除：删除等于把判断权交回给 Token 快照，那正是上面说的那个漏洞
            publish(userId, status, role);
            log.info("[AuthState] 登录纠正陈旧认证态: userId={}, {} -> {}",
                    userId, current, AuthState.format(status, role));
        } catch (Exception e) {
            log.warn("[AuthState] 登录纠正失败（不影响本次登录）userId={}", userId, e);
        }
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
