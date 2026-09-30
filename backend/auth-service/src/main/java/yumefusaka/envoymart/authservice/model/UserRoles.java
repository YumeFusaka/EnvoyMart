package yumefusaka.envoymart.authservice.model;

import java.util.Set;

/**
 * 角色常量的唯一出处。
 * <p>
 * 这些字符串同时出现在三个地方：注册时写默认值、管理端校验可设置的角色、启动同步时
 * 判断「哪些用户的角色不是默认值」。三处各写一遍字面量的话，任何一处写岔都<b>不会报错</b> ——
 * 注册会写进一个没人认得的角色，或者启动同步会漏掉本该补进 Redis 的用户。所以收在一处。
 */
public final class UserRoles {

    public static final String USER = "USER";
    public static final String ADMIN = "ADMIN";

    /** 可配置的角色集合。管理端拒绝除它以外的任何值。 */
    public static final Set<String> ASSIGNABLE = Set.of(USER, ADMIN);

    private UserRoles() {
    }
}
