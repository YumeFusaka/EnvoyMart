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
     * <b>为什么单列一个方法而不复用 {@link #chat}：这条分界线值得显式。</b>底层模型接口
     * 不执行工具——它把 tool_call 原样返回，不报错、正文为空。谁忘了走这条路，
     * 症状是「模型答非所问」，而不是一次异常，只能靠"断言工具真的被调用了"来防。
     * <p>
     * per-request 状态（循环护栏、高危确认、调用者身份）经 {@code toolContext} 传进循环，
     * 循环体内据此把关：<b>循环由实现层驱动，边界与放行判定归编排层</b>。
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
