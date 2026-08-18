package yumefusaka.envoymart.authservice.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

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
}
