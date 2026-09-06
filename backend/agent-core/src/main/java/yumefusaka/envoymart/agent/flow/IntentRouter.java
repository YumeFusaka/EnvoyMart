package yumefusaka.envoymart.agent.flow;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 意图路由 —— 判断一条消息是否该由某条确定性流程接管。
 * <p>
 * 分工是这个类的核心设计：
 * <ul>
 *   <li><b>模型判意图</b>——"退货政策第 3 条"和"订单 3 我要退货"的区别是<b>语义</b>的，
 *       拿正则去解语义问题必然误判（前者会被正则抓出数字 3 当成订单号）；</li>
 *   <li><b>规则验参数</b>——模型可能忽略"用户根本没给订单号"，所以还要用规则确认参数齐备；</li>
 *   <li><b>模型不可用则完全退回规则</b>。</li>
 * </ul>
 * 注意：路由可以用模型，但流程内部的<b>业务判定</b>（订单状态决定能不能退）必须由代码做。
 */
@Slf4j
public class IntentRouter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CLASSIFY_MAX_TOKENS = 64;
    private static final String NONE = "none";

    private final LLMProvider llmProvider;
    private final LLMConfig llmConfig;
    private final FlowRegistry flowRegistry;

    public IntentRouter(LLMProvider llmProvider, LLMConfig llmConfig, FlowRegistry flowRegistry) {
        this.llmProvider = llmProvider;
        this.llmConfig = llmConfig;
        this.flowRegistry = flowRegistry;
    }

    public Optional<DeterministicFlow> route(String userMessage) {
        if (userMessage == null || userMessage.isBlank() || flowRegistry.list().isEmpty()) {
            return Optional.empty();
        }

        if (!llmProvider.supportsReasoning()) {
            log.debug("[IntentRouter] LLM unavailable, fallback to rule matching");
            return flowRegistry.routeByRule(userMessage);
        }

        Optional<DeterministicFlow> byLlm = routeByLlm(userMessage);
        if (byLlm.isEmpty()) {
            return Optional.empty();
        }
        // 模型判定了意图，再用规则确认参数齐备（例如消息里是否真的给了订单号）
        if (!byLlm.get().matches(userMessage)) {
            log.info("[IntentRouter] LLM chose {} but required arguments are missing, fallback to agent",
                    byLlm.get().getName());
            return Optional.empty();
        }
        return byLlm;
    }

    private Optional<DeterministicFlow> routeByLlm(String userMessage) {
        String flowList = flowRegistry.list().stream()
                .map(flow -> "- " + flow.getName() + "：" + flow.getDescription())
                .reduce("", (a, b) -> a + b + "\n");

        List<ChatMessage> messages = List.of(
                ChatMessage.builder().role(ChatMessage.Role.SYSTEM)
                        .content("""
                                你是意图路由分类器。下面列出系统的确定性流程，判断用户请求是否属于其中某一条。
                                只输出 JSON，形如 {"flow":"流程名"} 或 {"flow":"none"}，不要任何解释。
                                拿不准一律返回 none —— 走流程会绕过模型直接执行，
                                误判的代价远大于漏判。

                                可用流程：
                                """ + flowList)
                        .build(),
                ChatMessage.builder().role(ChatMessage.Role.USER).content(userMessage).build()
        );

        try {
            // 分类是轻量任务：低温度、低 maxTokens，只输出一个标识符
            LLMConfig classifyConfig = LLMConfig.builder()
                    .model(llmConfig.getModel())
                    .temperature(0.0)
                    .maxTokens(CLASSIFY_MAX_TOKENS)
                    .build();
            LLMResponse response = llmProvider.chat(messages, classifyConfig);
            return parse(response.getContent());
        } catch (Exception e) {
            log.warn("[IntentRouter] LLM routing failed, fallback to rules: {}", e.getMessage());
            return flowRegistry.routeByRule(userMessage);
        }
    }

    private Optional<DeterministicFlow> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }

        Map<String, Object> parsed = MAPPER.readValue(raw.substring(start, end + 1), new TypeReference<>() {
        });
        Object flow = parsed.get("flow");
        if (flow == null || NONE.equalsIgnoreCase(String.valueOf(flow))) {
            return Optional.empty();
        }
        return flowRegistry.get(String.valueOf(flow));
    }
}
