package yumefusaka.envoymart.agent.memory;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 记忆条目 —— 可以是用户消息、AI 回复、抽取的事实或总结。
 */
@Data
@Builder
public class MemoryItem {
    private String id;
    private String sessionId;
    private String content;
    private Type type;
    @Builder.Default private Instant timestamp = Instant.now();

    public enum Type {
        MESSAGE,
        FACT,
        SUMMARY,
        PREFERENCE
    }
}
