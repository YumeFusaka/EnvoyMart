package yumefusaka.envoymart.orderservice.model.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 客服关闭工单，原因<b>必填</b>。
 * <p>
 * 与隐藏评价、禁用账号同一条规则：关闭是终结一个用户诉求的动作，
 * 少写一次原因，事后就永远答不出"这条为什么被关了"——而问这个问题的
 * 可能是用户，也可能是质控。用户自己确认解决不需要填，因为那本来就是
 * 他的诉求、他的决定。
 */
@Data
public class AdminTicketCloseRequest {

    @NotBlank(message = "关闭工单必须填写原因")
    @Size(max = 255, message = "原因最长 255 位")
    private String reason;
}
