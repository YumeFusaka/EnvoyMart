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

    /**
     * 归属用户 —— 长期记忆的隔离维度。
     * <p>
     * 短期窗口按 {@link #sessionId} 隔离（会话内上下文，本就该随会话消失）；
     * 长期记忆按 userId 隔离（跨会话，这才叫"长期"）。
     */
    private String userId;

    private String sessionId;
    private String content;
    private Type type;

    /**
     * 写入时刻。
     * <p>
     * 必须在召回时原样带回来 —— 曾经召回路径重新 builder 时不传该字段，
     * 于是所有历史条目在读出时时间戳都变成了"此刻"，任何基于新鲜度的策略都失去依据，
     * 而且不会报错，只会静默按错误前提计算。
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    public enum Type {
        MESSAGE,
        FACT,
        SUMMARY,
        PREFERENCE
    }
}
