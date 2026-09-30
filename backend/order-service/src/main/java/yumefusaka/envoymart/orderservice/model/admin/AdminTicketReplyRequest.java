package yumefusaka.envoymart.orderservice.model.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminTicketReplyRequest {

    @NotBlank(message = "回复内容不能为空")
    @Size(max = 2000, message = "回复最长 2000 位")
    private String content;
}
