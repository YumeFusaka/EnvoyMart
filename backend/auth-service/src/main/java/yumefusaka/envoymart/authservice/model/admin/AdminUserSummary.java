package yumefusaka.envoymart.authservice.model.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端的用户视图。
 * <p>
 * <b>刻意不从 {@code UserEntity} 直接返回</b>：那个实体带着 {@code password} 字段，
 * 只要有一个接口顺手 `Result.success(entity)`，口令哈希就跟着响应体出去了。
 * 哈希不是明文，但它仍然是可以离线暴力破解的资产——尤其当某天有人把 BCrypt 强度调低，
 * 或者换个不带盐的算法。这类泄漏不会报错、不会影响功能，只会在某次响应里静静躺着。
 * <p>
 * 类型层面的隔离比"记得手动 set 一遍"可靠：{@code AdminUserSummary} 里根本没有那个字段，
 * 想漏也漏不出去。
 */
@Data
@Builder
public class AdminUserSummary {

    private String id;
    private String username;
    private String nickname;
    private String avatar;
    private String phone;
    private String email;
    private String roleName;
    /** 0 禁用 / 1 正常 */
    private Integer status;
    private LocalDateTime createdAt;

    /** 禁用原因与操作人：恢复后为空 */
    private String disabledReason;
    private String disabledBy;
    private LocalDateTime disabledAt;
}
