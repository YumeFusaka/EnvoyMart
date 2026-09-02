package yumefusaka.envoymart.aiservice.service;

import yumefusaka.envoymart.aiservice.model.ChatRequest;
import yumefusaka.envoymart.aiservice.model.ChatResponse;

import java.util.function.Consumer;

public interface AiAssistantService {

    ChatResponse chat(String userId, ChatRequest request);

    /**
     * 流式对话：模型输出逐块回调，返回完整结果（含知识命中与工具轨迹）。
     */
    ChatResponse chatStream(String userId, ChatRequest request, Consumer<String> onChunk);
}
