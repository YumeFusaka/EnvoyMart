package yumefusaka.envoymart.aiservice.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank
    private String sessionId;
    @NotBlank
    private String message;
    private Long contextOrderId;

    /** 用户是否已确认高危操作（取消订单等），默认未确认 */
    private boolean approved = false;
}
