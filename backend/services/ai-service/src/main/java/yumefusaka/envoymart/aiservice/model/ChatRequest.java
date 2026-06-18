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
}
