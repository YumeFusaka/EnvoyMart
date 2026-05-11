package yumefusaka.envoymart.ai.service.impl;

import org.springframework.stereotype.Service;
import yumefusaka.envoymart.ai.model.request.ChatRequest;
import yumefusaka.envoymart.ai.model.response.ChatResponse;
import yumefusaka.envoymart.ai.model.response.ToolCallResponse;
import yumefusaka.envoymart.ai.provider.AiPrompt;
import yumefusaka.envoymart.ai.provider.AiProvider;
import yumefusaka.envoymart.ai.service.AiChatService;
import yumefusaka.envoymart.ai.tool.AiToolService;
import yumefusaka.envoymart.knowledge.model.KnowledgeSnippet;
import yumefusaka.envoymart.knowledge.service.KnowledgeService;
import yumefusaka.envoymart.product.model.response.ProductResponse;
import yumefusaka.envoymart.product.service.ProductService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AiChatServiceImpl implements AiChatService {

    private final Map<String, Deque<String>> sessionMemory = new ConcurrentHashMap<>();
    private final KnowledgeService knowledgeService;
    private final ProductService productService;
    private final AiToolService aiToolService;
    private final AiProvider aiProvider;

    public AiChatServiceImpl(KnowledgeService knowledgeService,
                             ProductService productService,
                             AiToolService aiToolService,
                             AiProvider aiProvider) {
        this.knowledgeService = knowledgeService;
        this.productService = productService;
        this.aiToolService = aiToolService;
        this.aiProvider = aiProvider;
    }

    @Override
    public ChatResponse chat(String userId, ChatRequest request) {
        Deque<String> memory = sessionMemory.computeIfAbsent(request.getSessionId(), key -> new ArrayDeque<>());
        List<String> memorySnapshot = new ArrayList<>(memory);
        List<KnowledgeSnippet> knowledge = knowledgeService.retrieve(request.getMessage(), 3);
        List<ToolCallResponse> toolCalls = aiToolService.collectToolCalls(userId, request.getMessage(), request.getContextOrderId());
        List<ProductResponse> recommendedProducts = productService.recommendProducts(request.getMessage(), 3);
        String reply = aiProvider.generateReply(AiPrompt.builder()
                .userMessage(request.getMessage())
                .memory(memorySnapshot)
                .knowledge(knowledge)
                .toolCalls(toolCalls)
                .recommendedProducts(recommendedProducts)
                .build());

        appendMemory(memory, "user:" + request.getMessage());
        appendMemory(memory, "assistant:" + reply);

        return ChatResponse.builder()
                .sessionId(request.getSessionId())
                .reply(reply)
                .knowledge(knowledge)
                .toolCalls(toolCalls)
                .recommendedProducts(recommendedProducts)
                .build();
    }

    private void appendMemory(Deque<String> memory, String message) {
        memory.addLast(message);
        while (memory.size() > 8) {
            memory.removeFirst();
        }
    }
}
