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

    /**
     * 流式版本的 {@link #chatWithTools}。
     * <p>
     * <b>返回本次真正执行过的工具</b>，与 {@link #chatWithTools} 通过
     * {@link LLMResponse#getToolExecutions()} 返回是同一件事。
     * 这里之所以要显式返回而不是像 {@link #chat} 那样把结果塞进返回值：
     * 文本增量是<b>边生成边推</b>的，推完就没有第二份了，所以正文只能走回调，
     * 剩下的工具轨迹就只剩返回值这一条路。
     * <p>
     * 曾经它是 {@code void}，于是流式下的 ReAct 轨迹整段丢失——前端看不到调用记录，
     * 后置校验还会把一条<b>明明有工具依据</b>的回答判成「无依据」而挂上横幅。
     */
    default List<ToolExecution> chatStreamWithTools(List<ChatMessage> messages, LLMConfig config,
                                                    java.util.Map<String, Object> toolContext,
                                                    java.util.function.Consumer<String> onChunk) {
        // 回退到非流式的带工具版本：工具照常执行，正文一次性经 onChunk 推出。
        // 退到 chatStream 是错的——那条路连工具定义都不下发，整条 ReAct 往返悄无声息地消失，
        // 症状是「同一个问题走流式不查订单、走非流式查」，两条路径都不报错。
        LLMResponse response = chatWithTools(messages, config, toolContext);
        if (response.getContent() != null) {
            onChunk.accept(response.getContent());
        }
        return response.getToolExecutions() == null ? List.of() : response.getToolExecutions();
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

    /**
     * 规划 + 检索意图，<b>一次调用两件事</b>。
     * <p>
     * 默认实现退回只做规划、且假定需要检索（{@link PlanWithIntent#of}）：
     * 不知道意图时按老办法走，是最不容易出错的一侧——多检索一次只是慢一点，
     * 而漏检索会让模型拿不到依据、只能拒答或编。
     * <p>
     * 真实实现（{@code LangChain4jLLMProvider}）在规划提示词里多要一个
     * {@code needRetrieval} 字段，因此<b>不多花一次调用</b>。
     */
    default PlanWithIntent planWithIntent(String userMessage,
                                          List<yumefusaka.envoymart.agent.tool.ToolDefinition> availableTools,
                                          String context) {
        return PlanWithIntent.of(plan(userMessage, availableTools, context));
    }

    /**
     * 对一份失败/空结果的执行轨迹做一次「自我诊断」，判断接下来该换什么策略。
     * <p>
     * <b>为什么要在重规划之外单独加一层</b>：重规划问的是「下一步做什么」，模型的默认反应
     * 是把上一步原样再试一遍（提示词里那句「不要用同样的参数重发」就是在补这个洞）。
     * 但真正有效的修法取决于<b>失败的性质</b>：参数写错要改参数，工具选错要换工具，
     * 而「数据里根本没有」则应当停止重试、如实作答。这三者从一句「无结果」里分不出来，
     * 所以要先让模型把「为什么失败」说出来，再据此给重规划一个明确方向。
     * <p>
     * 默认不提供该能力（返回 {@code null}）时，重规划退回原先那段通用上下文——
     * 行为与没有这一层时逐位一致。
     *
     * @return 一句诊断；无法诊断时返回 null，调用方退回通用重规划
     */
    default String critique(String userMessage, String executionTrace,
                            java.util.List<yumefusaka.envoymart.agent.tool.ToolDefinition> availableTools) {
        return null;
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
