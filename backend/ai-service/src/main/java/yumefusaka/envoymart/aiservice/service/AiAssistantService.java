package yumefusaka.envoymart.aiservice.service;

import yumefusaka.envoymart.aiservice.model.ChatRequest;
import yumefusaka.envoymart.aiservice.model.ChatResponse;

public interface AiAssistantService {

    ChatResponse chat(String userId, ChatRequest request);
}
