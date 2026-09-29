package yumefusaka.envoymart.authservice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_]{4,20}$", message = "用户名需为 4-20 位字母、数字或下划线")
    private String username;

    /** 要求同时含字母与数字：纯数字口令在撞库面前等同于没有口令 */
    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S{8,32}$",
            message = "密码需为 8-32 位，且同时包含字母和数字")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 32, message = "昵称最长 32 位")
    private String nickname;

    /** 选填。留空是允许的，因此正则里用 `^$|` 放过空串 */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
