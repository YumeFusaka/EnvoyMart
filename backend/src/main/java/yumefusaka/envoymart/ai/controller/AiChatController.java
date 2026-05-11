package yumefusaka.envoymart.ai.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.ai.model.request.ChatRequest;
import yumefusaka.envoymart.ai.model.response.ChatResponse;
import yumefusaka.envoymart.ai.service.AiChatService;
import yumefusaka.envoymart.common.context.BaseContext;
import yumefusaka.envoymart.common.result.Result;

@RestController
@RequestMapping("/ai")
public class AiChatController {

    private final AiChatService aiChatService;

    public AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping("/chat")
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return Result.success(aiChatService.chat(BaseContext.getCurrentId(), request));
    }
}
