package yumefusaka.envoymart.ai.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank(message = "会话 ID 不能为空")
    private String sessionId;

    @NotBlank(message = "消息内容不能为空")
    private String message;

    private Long contextOrderId;
}
