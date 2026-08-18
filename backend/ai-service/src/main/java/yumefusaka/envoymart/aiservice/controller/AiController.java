package yumefusaka.envoymart.aiservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.aiservice.model.ChatRequest;
import yumefusaka.envoymart.aiservice.model.ChatResponse;
import yumefusaka.envoymart.aiservice.service.AiAssistantService;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;

@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiAssistantService aiAssistantService;

    public AiController(AiAssistantService aiAssistantService) {
        this.aiAssistantService = aiAssistantService;
    }

    @PostMapping("/chat")
    public Result<ChatResponse> chat(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                     @Valid @RequestBody ChatRequest request) {
        return Result.success(aiAssistantService.chat(userId, request));
    }
}
