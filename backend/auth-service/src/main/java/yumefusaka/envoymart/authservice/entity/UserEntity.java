package yumefusaka.envoymart.authservice.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class UserEntity {

    /** {@link #status} 的取值。登录链路、管理端、启动同步三处都引用这里，少一处能写岔的字面量 */
    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_ENABLED = 1;

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
    /** 禁用原因与操作人：恢复时三列一起清空 */
    private String disabledReason;
    private String disabledBy;
    private LocalDateTime disabledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
