package yumefusaka.envoymart.auth.model.entity;

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
    private LocalDateTime createdAt;
}
