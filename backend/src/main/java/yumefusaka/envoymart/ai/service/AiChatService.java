package yumefusaka.envoymart.ai.service;

import yumefusaka.envoymart.ai.model.request.ChatRequest;
import yumefusaka.envoymart.ai.model.response.ChatResponse;

public interface AiChatService {

    ChatResponse chat(String userId, ChatRequest request);
}
