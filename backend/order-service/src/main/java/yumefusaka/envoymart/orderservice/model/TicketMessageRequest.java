package yumefusaka.envoymart.orderservice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 用户侧追加一条消息；重开时也用它承载可选的"为什么重开" */
@Data
public class TicketMessageRequest {

    @NotBlank(message = "内容不能为空")
    @Size(max = 2000, message = "内容最长 2000 位")
    private String content;
}
