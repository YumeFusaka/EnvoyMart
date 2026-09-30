package yumefusaka.envoymart.authservice.model.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 启用 / 禁用用户。
 * <p>
 * 用 0/1 而不是 "ENABLED"/"DISABLED"：库里就是 tinyint，登录链路也一直按
 * {@code status != 1} 判断，多一层字符串映射只会多一处能写岔的地方。
 */
@Data
public class UserStatusRequest {

    /** 0 禁用 / 1 正常。非法值在服务层拒绝（这里只挡 null） */
    @NotNull(message = "状态不能为空")
    private Integer status;

    /**
     * 禁用原因，<b>禁用时必填</b>；启用时忽略。
     * <p>
     * 与评价隐藏同一个道理：禁用是管理端唯一能让一个用户彻底用不了系统的动作，
     * 事后必须答得出是谁、什么时候、因为什么做的。这条必填在服务层校验——
     * 它依赖 status 的值，注解表达不了这种条件关系。
     */
    @Size(max = 255, message = "原因最长 255 字")
    private String reason;
}
