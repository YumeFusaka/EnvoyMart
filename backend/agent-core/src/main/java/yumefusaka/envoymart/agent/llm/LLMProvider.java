package yumefusaka.envoymart.agent.llm;

import java.util.List;

/**
 * LLM 提供者抽象层 —— 对接任意大模型（OpenAI、Claude、本地模型等）。
 * <p>
 * 实现者只需将 ChatMessage 列表序列化为对应 API 格式并返回 LLMResponse。
 */
public interface LLMProvider {

    /**
     * 是否具备真实推理能力。
     * <p>
     * Mock 实现返回 false，上层据此跳过需要模型参与的环节
     * （如意图路由），直接走规则降级路径。
     */
    default boolean supportsReasoning() {
        return true;
    }

    LLMResponse chat(List<ChatMessage> messages, LLMConfig config);

    /**
     * 带工具上下文的调用。
     * <p>
     * 工具上下文会随每次工具调用传回给 {@code ToolCallback}，
     * 用于传递 per-request 的状态——例如循环护栏（预算、重复检测）。
     * 这样即使工具循环由框架驱动，循环的边界仍归我们控制。
     */
    default LLMResponse chat(List<ChatMessage> messages, LLMConfig config, java.util.Map<String, Object> toolContext) {
        return chat(messages, config);
    }

    /**
     * 生成多步执行计划。默认不提供规划能力，PAE 引擎会回退到关键词规则。
     *
     * @param availableTools 已注册工具清单，计划只能引用其中的工具
     * @param context        系统提示词（含 RAG 知识与用户长期记忆），规划时可参考
     */
    default List<PlanStep> plan(String userMessage,
                                List<yumefusaka.envoymart.agent.tool.ToolDefinition> availableTools,
                                String context) {
        return List.of();
    }

    /** 流式变体，逐块推送。默认回退到非流式。 */
    default void chatStream(List<ChatMessage> messages, LLMConfig config, java.util.function.Consumer<String> onChunk) {
        LLMResponse resp = chat(messages, config);
        if (resp.getContent() != null) {
            onChunk.accept(resp.getContent());
        }
    }

    /** 带工具上下文的流式变体。 */
    default void chatStream(List<ChatMessage> messages, LLMConfig config,
                            java.util.Map<String, Object> toolContext,
                            java.util.function.Consumer<String> onChunk) {
        chatStream(messages, config, onChunk);
    }
}
