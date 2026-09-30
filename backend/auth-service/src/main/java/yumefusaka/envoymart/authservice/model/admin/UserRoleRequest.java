package yumefusaka.envoymart.authservice.model.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 调整用户的角色。
 * <p>
 * 合法值只有 {@code UserRoles.ASSIGNABLE} 里那几个，在服务层校验并按大写归一——
 * 角色字符串会一路进 JWT 的 claim、再由网关读出来判权限，写进一个没人认得的角色，
 * 表现是「这个管理员什么也管不了」或者反过来「一个不该有权限的人拿到了权限」，
 * 两种都不会在写入时报错。
 */
@Data
public class UserRoleRequest {

    @NotBlank(message = "角色不能为空")
    @Size(max = 32, message = "角色名最长 32 位")
    private String role;
}
