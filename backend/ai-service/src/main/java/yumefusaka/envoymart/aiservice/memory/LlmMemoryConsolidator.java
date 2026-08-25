package yumefusaka.envoymart.aiservice.memory;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.memory.MemoryConsolidator;
import yumefusaka.envoymart.agent.memory.MemoryItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于 LLM 的记忆固化器 —— 从最近对话里抽取值得长期记住的事实与偏好。
 * <p>
 * 只抽「跨会话仍然成立」的信息（如"用户是学生党，预算 100 元左右"），
 * 不抽一次性意图（如"查一下订单 3"），避免长期记忆被噪声淹没。
 */
@Slf4j
public class LlmMemoryConsolidator implements MemoryConsolidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_INPUT_CHARS = 4000;

    private final LLMProvider llmProvider;
    private final LLMConfig defaultConfig;

    public LlmMemoryConsolidator(LLMProvider llmProvider, LLMConfig defaultConfig) {
        this.llmProvider = llmProvider;
        this.defaultConfig = defaultConfig;
    }

    @Override
    public List<MemoryItem> extract(String sessionId, List<MemoryItem> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return List.of();
        }

        StringBuilder transcript = new StringBuilder();
        for (MemoryItem item : recentMessages) {
            transcript.append(item.getContent()).append('\n');
        }
        String text = transcript.length() > MAX_INPUT_CHARS
                ? transcript.substring(transcript.length() - MAX_INPUT_CHARS)
                : transcript.toString();

        List<ChatMessage> messages = List.of(
                ChatMessage.builder().role(ChatMessage.Role.SYSTEM)
                        .content("""
                                你是用户画像抽取器。从对话中提取「跨会话仍然成立」的用户事实与偏好，
                                例如身份、预算区间、品牌/品类偏好、收货地、服务偏好。
                                不要提取一次性意图（查询、下单动作）或助手说的话。
                                输出 JSON 数组，每个元素形如 {"content":"用户是学生党，预算 100 元左右","type":"PREFERENCE"}，
                                type 取值 FACT 或 PREFERENCE。没有可提取内容就返回 []。只输出 JSON。
                                """)
                        .build(),
                ChatMessage.builder().role(ChatMessage.Role.USER).content(text).build()
        );

        try {
            // 抽取要确定性输出，复用全局配置的模型名，只覆盖温度
            LLMConfig extractConfig = LLMConfig.builder()
                    .model(defaultConfig.getModel())
                    .temperature(0.0)
                    .maxTokens(defaultConfig.getMaxTokens())
                    .build();
            LLMResponse response = llmProvider.chat(messages, extractConfig);
            List<MemoryItem> items = parse(sessionId, response.getContent());
            log.info("[MemoryConsolidator] session={} extracted {} item(s)", sessionId, items.size());
            return items;
        } catch (Exception e) {
            log.warn("[MemoryConsolidator] extract failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<MemoryItem> parse(String sessionId, String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }

        List<Map<String, Object>> items = MAPPER.readValue(
                raw.substring(start, end + 1), new TypeReference<>() {
                });

        List<MemoryItem> result = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Object content = item.get("content");
            if (content == null || String.valueOf(content).isBlank()) {
                continue;
            }
            result.add(MemoryItem.builder()
                    .id(UUID.randomUUID().toString())
                    .sessionId(sessionId)
                    .content(String.valueOf(content))
                    .type(parseType(item.get("type")))
                    .build());
        }
        return result;
    }

    private MemoryItem.Type parseType(Object raw) {
        if (raw == null) {
            return MemoryItem.Type.FACT;
        }
        try {
            return MemoryItem.Type.valueOf(String.valueOf(raw).toUpperCase());
        } catch (IllegalArgumentException e) {
            return MemoryItem.Type.FACT;
        }
    }
}
