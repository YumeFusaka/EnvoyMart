package yumefusaka.envoymart.authservice.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class UserEntity {

    @TableId
    private String id;
    private String username;
    private String password;
    private String nickname;
    private String roleName;
    private String avatar;
    private String phone;
    private String email;
    /** 0 禁用 / 1 正常。禁用后拒绝登录，但历史订单仍可查询 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
