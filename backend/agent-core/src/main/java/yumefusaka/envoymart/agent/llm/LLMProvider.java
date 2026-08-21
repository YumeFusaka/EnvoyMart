package yumefusaka.envoymart.agent.llm;

import java.util.List;

/**
 * LLM 提供者抽象层 —— 对接任意大模型（OpenAI、Claude、本地模型等）。
 * <p>
 * 实现者只需将 ChatMessage 列表序列化为对应 API 格式并返回 LLMResponse。
 */
public interface LLMProvider {

    LLMResponse chat(List<ChatMessage> messages, LLMConfig config);

    /**
     * 生成多步执行计划。默认不提供规划能力，PAE 引擎会回退到关键词规则。
     *
     * @param availableTools 已注册工具清单，计划只能引用其中的工具
     */
    default List<PlanStep> plan(String userMessage, List<yumefusaka.envoymart.agent.tool.ToolDefinition> availableTools) {
        return List.of();
    }

    /** 流式变体，逐块推送。默认回退到非流式。 */
    default void chatStream(List<ChatMessage> messages, LLMConfig config, java.util.function.Consumer<String> onChunk) {
        LLMResponse resp = chat(messages, config);
        if (resp.getContent() != null) {
            onChunk.accept(resp.getContent());
        }
    }
}
