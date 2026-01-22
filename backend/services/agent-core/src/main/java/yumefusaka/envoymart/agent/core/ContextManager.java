package yumefusaka.envoymart.agent.core;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文管理器 —— 控制输入给 LLM 的消息窗口和 Token 预算。
 * <p>
 * 策略：
 * - 保留 system 消息始终在首
 * - 截断时优先丢弃中间的历史对话，保留最近 N 轮 + system 消息
 * - 上下文过长时触发摘要压缩
 */
@Slf4j
public class ContextManager {

    private final Config config;

    public ContextManager(Config config) {
        this.config = config;
    }

    /**
     * 修剪消息列表以适应上下文窗口。
     */
    public List<ChatMessage> trim(List<ChatMessage> messages) {
        if (messages.size() <= config.maxRounds * 2 + 1) {
            return messages;
        }

        List<ChatMessage> result = new ArrayList<>();
        // 保留 system 消息
        messages.stream()
                .filter(m -> m.getRole() == ChatMessage.Role.SYSTEM)
                .forEach(result::add);

        // 保留最近的 N 轮对话
        List<ChatMessage> recent = messages.subList(
                Math.max(0, messages.size() - config.maxRounds * 2),
                messages.size());

        // 如果 system 消息已包含在 recent 中则去重
        for (ChatMessage msg : recent) {
            if (msg.getRole() == ChatMessage.Role.SYSTEM) continue;
            result.add(msg);
        }

        log.debug("[ContextManager] trimmed {} → {} messages", messages.size(), result.size());
        return result;
    }

    /**
     * 估算消息的 Token 数（近似）。
     * ponytail: 简单的字符/4 估算，生产环境使用对应模型的 tokenizer。
     */
    public int estimateTokens(List<ChatMessage> messages) {
        return messages.stream()
                .mapToInt(m -> (m.getContent() != null ? m.getContent().length() : 0) / 4)
                .sum();
    }

    @Data
    @Builder
    public static class Config {
        @Builder.Default private int maxRounds = 10;       // 保留最近多少轮对话
        @Builder.Default private int maxTokens = 4096;     // 上下文窗口上限
    }
}
