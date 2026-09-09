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
     * 带工具上下文的<b>单次</b>调用，不驱动工具循环。
     * <p>
     * 规划、意图分类、记忆抽取这类内部调用走这里：它们只要一段文本或一个 JSON，
     * 把工具定义一并下发只会让模型误选工具，而响应里的 tool_call 没有任何人消费。
     */
    default LLMResponse chat(List<ChatMessage> messages, LLMConfig config, java.util.Map<String, Object> toolContext) {
        return chat(messages, config);
    }

    /**
     * 带工具循环的对话 —— ReAct 的落点。
     * <p>
     * 与 {@link #chat} 的区别是「会不会执行工具」：这里是完整的
     * 「模型返回 tool_call → 执行 → 回填结果 → 再调用模型」往返，直到模型不再要求调用工具。
     * <p>
     * <b>工具上下文随每次工具调用传回 {@code ToolCallback}</b>，用来把 per-request 的
     * 状态（循环护栏、高危确认、调用者身份）送进工具执行点——所以循环由框架驱动，
     * 但循环的边界与放行判定仍在编排层手里。
     * <p>
     * Spring AI 2.0 把工具执行循环从 {@code ChatModel} 上移除了（移到了 {@code ChatClient}
     * 的 {@code ToolCallingAdvisor}），直接调 {@code ChatModel.call()} 时工具永远不会被执行，
     * 且不报错、只返回空内容。这个方法是那条分界线的显式表达。
     */
    default LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                      java.util.Map<String, Object> toolContext) {
        return chat(messages, config, toolContext);
    }

    /** 流式版本的 {@link #chatWithTools}。 */
    default void chatStreamWithTools(List<ChatMessage> messages, LLMConfig config,
                                     java.util.Map<String, Object> toolContext,
                                     java.util.function.Consumer<String> onChunk) {
        chatStream(messages, config, toolContext, onChunk);
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
