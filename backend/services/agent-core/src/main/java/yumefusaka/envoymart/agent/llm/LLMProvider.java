package yumefusaka.envoymart.agent.llm;

import java.util.List;

/**
 * LLM 提供者抽象层 —— 对接任意大模型（OpenAI、Claude、本地模型等）。
 * <p>
 * 实现者只需将 ChatMessage 列表序列化为对应 API 格式并返回 LLMResponse。
 */
public interface LLMProvider {

    LLMResponse chat(List<ChatMessage> messages, LLMConfig config);

    /** 流式变体，逐块推送。默认回退到非流式。 */
    default void chatStream(List<ChatMessage> messages, LLMConfig config, java.util.function.Consumer<String> onChunk) {
        LLMResponse resp = chat(messages, config);
        if (resp.getContent() != null) {
            onChunk.accept(resp.getContent());
        }
    }
}
