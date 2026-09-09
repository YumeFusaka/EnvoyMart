package yumefusaka.envoymart.aiservice.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.aiservice.tool.ToolRegistryToolCallback;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI 接入层 —— 把 agent-core 的 LLMProvider 契约适配到 Spring AI 的 ChatModel。
 * <p>
 * <b>两条路径，分界线是"要不要执行工具"</b>：
 * <ul>
 *   <li>{@link #chat} —— 单次调用，不挂工具回调。规划、意图分类、记忆抽取走这里，
 *       它们只要一段文本或一个 JSON，挂上工具定义只会让模型误选。</li>
 *   <li>{@link #chatWithTools} —— 完整工具循环，走 {@code ChatClient} 的
 *       {@code ToolCallingAdvisor}。Spring AI 2.0 起这条循环已从所有 {@code ChatModel}
 *       上移除，只在 advisor 链里存在；直接调 {@code ChatModel.call()} 时模型返回的
 *       tool_call 不会被执行，也不报错。</li>
 * </ul>
 * 两条路径的工具执行都走 agent-core 的 {@code ToolRegistry} 并记录调用轨迹；
 * 循环的边界（预算、重复检测、高危确认、调用者身份）经 toolContext 下发，由编排层决定。
 */
@Slf4j
public class SpringAiLLMProvider implements LLMProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    /** 全局默认调用配置（模型名等），规划这类内部调用也复用它 */
    private final LLMConfig defaultConfig;
    /** 模型调用的耗时与 token 走指标而不是只写日志——日志适合排查单次，指标才能看出趋势与成本 */
    private final MeterRegistry meterRegistry;

    /** 只在 ReAct 路径上用到的带工具循环客户端，惰性构建 */
    private volatile ChatClient chatClient;

    public SpringAiLLMProvider(ChatModel chatModel, ToolRegistry toolRegistry, LLMConfig defaultConfig,
                               MeterRegistry meterRegistry) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.defaultConfig = defaultConfig;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
        return chat(messages, config, Map.of());
    }

    /**
     * 单次调用，<b>不挂工具回调</b>。
     * <p>
     * 规划、意图分类、记忆抽取走这里。早先无差别地把全部工具定义挂在每次调用上，
     * 这三类调用本只要一段文本或一个 JSON，却因此可能返回 tool_call——而响应里的
     * tool_call 在单次调用路径上没有任何人消费，等于白费一次调用。
     */
    @Override
    public LLMResponse chat(List<ChatMessage> messages, LLMConfig config, Map<String, Object> toolContext) {
        long startedAt = System.nanoTime();
        ChatResponse response = chatModel.call(new Prompt(toSpringMessages(messages), buildOptions(config, List.of())));
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        return toLLMResponse(response, config, latencyMs, List.of());
    }

    /**
     * ReAct 落点：经 {@code ChatClient} 的 {@code ToolCallingAdvisor} 驱动完整工具循环。
     * <p>
     * Spring AI 2.0 起，工具执行循环已从所有 {@code ChatModel} 上移除，只在 {@code ChatClient}
     * 的 advisor 链里存在。直接调 {@code ChatModel.call()} 时模型返回的 tool_call 不会被执行，
     * 而且不报错、内容为空——这是本次改造要消除的静默失效。
     */
    @Override
    public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                     Map<String, Object> toolContext) {
        List<ToolExecution> executions = new ArrayList<>();
        ChatOptions options = buildOptions(config, toToolCallbacks(executions), toolContext);

        long startedAt = System.nanoTime();
        ChatResponse response = chatClient()
                .prompt(new Prompt(toSpringMessages(messages), options))
                .call()
                .chatClientResponse()
                .chatResponse();
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        return toLLMResponse(response, config, latencyMs, executions);
    }

    /**
     * 惰性构建带工具循环的 {@code ChatClient}。
     * <p>
     * 显式挂 {@code ToolCallingAdvisor} 而不依赖自动装配：工具循环是这条路径的<b>全部意义</b>，
     * 隐式依赖一旦随版本变化失效，表现又是"静默返回空"，排查成本极高。
     */
    private ChatClient chatClient() {
        ChatClient local = chatClient;
        if (local == null) {
            synchronized (this) {
                local = chatClient;
                if (local == null) {
                    local = ChatClient.builder(chatModel)
                            .defaultAdvisors(ToolCallingAdvisor.builder().build())
                            .build();
                    chatClient = local;
                }
            }
        }
        return local;
    }

    private LLMResponse toLLMResponse(ChatResponse response, LLMConfig config, long latencyMs,
                                      List<ToolExecution> executions) {
        AssistantMessage output = response.getResult().getOutput();
        List<ChatMessage.ToolCallRequest> toolCalls = output.getToolCalls() == null
                ? List.of()
                : output.getToolCalls().stream()
                .map(tc -> ChatMessage.ToolCallRequest.builder()
                        .id(tc.id())
                        .name(tc.name())
                        .arguments(parseArguments(tc.arguments()))
                        .build())
                .toList();

        int promptTokens = promptTokens(response);
        int completionTokens = completionTokens(response);
        // 成本可观测：每次模型调用的耗时、token 消耗与工具调用数
        log.info("[LLM] model={} latencyMs={} promptTokens={} completionTokens={} toolCalls={} toolExecutions={}",
                config.getModel(), latencyMs, promptTokens, completionTokens,
                toolCalls.size(), executions.size());
        recordLlmMetrics(config.getModel(), false, latencyMs, promptTokens, completionTokens);

        return LLMResponse.builder()
                .content(output.getText())
                .toolCalls(toolCalls)
                .toolExecutions(executions)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .finishReason(toolCalls.isEmpty()
                        ? LLMResponse.FinishReason.STOP
                        : LLMResponse.FinishReason.TOOL_CALL)
                .build();
    }

    /** 单次流式，不驱动工具循环。 */
    @Override
    public void chatStream(List<ChatMessage> messages, LLMConfig config, java.util.function.Consumer<String> onChunk) {
        chatStream(messages, config, Map.of(), onChunk);
    }

    @Override
    public void chatStream(List<ChatMessage> messages, LLMConfig config, Map<String, Object> toolContext,
                           java.util.function.Consumer<String> onChunk) {
        // 单次流式，不挂工具：与 chat(messages, config, toolContext) 同一类内部调用
        streamInternal(messages, config, List.of(), onChunk);
    }

    /**
     * 流式版本的 ReAct —— 工具循环由 {@code ChatClient} 的 advisor 驱动。
     * <p>
     * advisor 在每轮工具往返结束后才把最终回答推下来，因此首字延迟仍然只取决于
     * 最终回答的首个 token，而不是整轮工具编排的总时长。
     */
    @Override
    public void chatStreamWithTools(List<ChatMessage> messages, LLMConfig config,
                                    Map<String, Object> toolContext,
                                    java.util.function.Consumer<String> onChunk) {
        List<ToolExecution> executions = new ArrayList<>();
        ChatOptions options = buildOptions(config, toToolCallbacks(executions), toolContext);

        long startedAt = System.nanoTime();
        StringBuilder full = new StringBuilder();
        chatClient().prompt(new Prompt(toSpringMessages(messages), options))
                .stream()
                .chatClientResponse()
                .toIterable()
                .forEach(clientResponse -> {
                    if (clientResponse.chatResponse() == null || clientResponse.chatResponse().getResult() == null) {
                        return;
                    }
                    String text = clientResponse.chatResponse().getResult().getOutput().getText();
                    if (text != null && !text.isEmpty()) {
                        full.append(text);
                        onChunk.accept(text);
                    }
                });

        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("[LLM] stream+tools model={} latencyMs={} chars={} toolExecutions={}",
                config.getModel(), latencyMs, full.length(), executions.size());
        recordLlmMetrics(config.getModel(), true, latencyMs, 0, 0);
    }

    private void streamInternal(List<ChatMessage> messages, LLMConfig config,
                                List<ToolCallback> callbacks, java.util.function.Consumer<String> onChunk) {
        long startedAt = System.nanoTime();
        StringBuilder full = new StringBuilder();
        chatModel.stream(new Prompt(toSpringMessages(messages), buildOptions(config, callbacks)))
                .toIterable()
                .forEach(response -> {
                    AssistantMessage output = response.getResult() == null ? null : response.getResult().getOutput();
                    String text = output == null ? null : output.getText();
                    if (text != null && !text.isEmpty()) {
                        full.append(text);
                        onChunk.accept(text);
                    }
                });

        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("[LLM] stream model={} latencyMs={} chars={}",
                config.getModel(), latencyMs, full.length());
        // 流式拿不到 token 用量，只记耗时；stream=true 与同步调用分开看，否则首字延迟会被整轮时长污染
        recordLlmMetrics(config.getModel(), true, latencyMs, 0, 0);
    }

    /**
     * 模型调用的耗时与 token 指标。
     * <p>
     * 日志回答"这一次发生了什么"，指标回答"最近一周贵在哪"——模型是按 token 计费的，
     * 没有按模型的用量趋势就无从谈成本控制。
     */
    private void recordLlmMetrics(String model, boolean stream, long latencyMs,
                                  int promptTokens, int completionTokens) {
        if (meterRegistry == null) {
            return;
        }
        String tag = stream ? "stream" : "sync";
        Timer.builder("agent.llm.latency")
                .tag("model", model).tag("mode", tag)
                .register(meterRegistry)
                .record(java.time.Duration.ofMillis(latencyMs));
        if (promptTokens > 0) {
            Counter.builder("agent.llm.tokens")
                    .tag("model", model).tag("type", "prompt")
                    .register(meterRegistry).increment(promptTokens);
        }
        if (completionTokens > 0) {
            Counter.builder("agent.llm.tokens")
                    .tag("model", model).tag("type", "completion")
                    .register(meterRegistry).increment(completionTokens);
        }
    }

    /**
     * 以模型自带的默认选项为模板改写，避免把通用 ChatOptions 强塞给具体模型实现
     * （OpenAI 等实现要求自己的 Options 类型）。
     */
    private ChatOptions buildOptions(LLMConfig config, List<ToolCallback> callbacks) {
        return buildOptions(config, callbacks, Map.of());
    }

    private ChatOptions buildOptions(LLMConfig config, List<ToolCallback> callbacks,
                                     Map<String, Object> toolContext) {
        ChatOptions defaults = chatModel.getOptions();
        ChatOptions.Builder<?> builder = defaults == null ? ChatOptions.builder() : defaults.mutate();

        builder.model(config.getModel())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens());

        if (builder instanceof ToolCallingChatOptions.Builder<?> toolBuilder) {
            if (!callbacks.isEmpty()) {
                toolBuilder.toolCallbacks(callbacks);
            }
            // per-request 上下文（如循环护栏）随工具调用传回 ToolCallback
            if (!toolContext.isEmpty()) {
                toolBuilder.toolContext(toolContext);
            }
        }
        return builder.build();
    }

    @Override
    public List<PlanStep> plan(String userMessage, List<ToolDefinition> availableTools, String context) {
        if (availableTools.isEmpty()) {
            return List.of();
        }

        String toolList = availableTools.stream()
                .map(d -> "- " + d.getName() + ": " + d.getDescription())
                .reduce("", (a, b) -> a + b + "\n");

        String background = (context == null || context.isBlank())
                ? "" : "\n已知背景（可据此补全工具参数）：\n" + context + "\n";

        List<ChatMessage> messages = List.of(
                ChatMessage.builder().role(ChatMessage.Role.SYSTEM)
                        .content("""
                                你是电商客服任务规划器。根据用户请求和可用工具，输出一个 JSON 数组作为执行计划。
                                每个元素形如 {"tool":"工具名","arguments":{"参数名":"值"},"reason":"这一步要达成什么",
                                "optional":false,"dependsOn":[]}。
                                规则：只能使用下面列出的工具；工具参数尽量从用户请求与已知背景中提取；
                                不需要多步就返回只含一个元素的数组；无法完成则返回 []。
                                dependsOn 填「本步骤依赖的步骤序号」（从 0 开始）：
                                只有需要用到前面某一步的结果时才填，互不依赖的步骤留空数组，
                                这样它们会被并发执行。例如先查订单再取消，取消那步就要依赖查询步。
                                只输出 JSON，不要任何解释。

                                可用工具：
                                """ + toolList + background)
                        .build(),
                ChatMessage.builder().role(ChatMessage.Role.USER).content(userMessage).build()
        );

        try {
            // 规划要确定性输出，复用全局配置的模型名，只覆盖温度
            LLMConfig planConfig = LLMConfig.builder()
                    .model(defaultConfig.getModel())
                    .temperature(0.0)
                    .maxTokens(defaultConfig.getMaxTokens())
                    .build();
            LLMResponse response = chat(messages, planConfig);
            return parsePlan(response.getContent());
        } catch (Exception e) {
            log.warn("[SpringAiLLMProvider] plan failed, fallback to rule-based: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<PlanStep> parsePlan(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String json = raw.trim();
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        List<Map<String, Object>> items = MAPPER.readValue(
                json.substring(start, end + 1), new TypeReference<>() {
                });
        List<PlanStep> steps = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Object tool = item.get("tool");
            if (tool == null) {
                continue;
            }
            steps.add(PlanStep.builder()
                    .tool(String.valueOf(tool))
                    .arguments((Map<String, Object>) item.getOrDefault("arguments", Map.of()))
                    .reason(String.valueOf(item.getOrDefault("reason", "")))
                    .optional(Boolean.TRUE.equals(item.get("optional")))
                    .dependsOn(parseDependsOn(item.get("dependsOn"), steps.size()))
                    .build());
        }
        return steps;
    }

    /**
     * 解析步骤依赖。
     * <p>
     * 只接受<b>指向更早步骤</b>的合法下标：指向自己或指向后面的步骤都会让分层执行
     * 陷入环或执行空转，宁可当作无依赖也不要把它带进执行阶段。
     */
    private List<Integer> parseDependsOn(Object raw, int currentIndex) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).intValue())
                .filter(index -> index >= 0 && index < currentIndex)
                .distinct()
                .toList();
    }

    /**
     * 把 ToolRegistry 里的工具包装成 Spring AI 的 ToolCallback。
     * 执行时仍然调用我们的 Tool，并记录轨迹供上层展示。
     */
    private List<ToolCallback> toToolCallbacks(List<ToolExecution> sink) {
        return toolRegistry.listDefinitions().stream()
                .map(def -> (ToolCallback) new ToolRegistryToolCallback(toolRegistry, def, sink, meterRegistry))
                .toList();
    }

    private List<Message> toSpringMessages(List<ChatMessage> messages) {
        List<Message> result = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            String content = message.getContent() == null ? "" : message.getContent();
            switch (message.getRole()) {
                case SYSTEM -> result.add(new SystemMessage(content));
                case USER -> result.add(new UserMessage(content));
                case ASSISTANT -> {
                    if (message.getToolCall() != null) {
                        ChatMessage.ToolCallRequest call = message.getToolCall();
                        result.add(AssistantMessage.builder()
                                .content(content)
                                .toolCalls(List.of(new AssistantMessage.ToolCall(
                                        call.getId(), "function", call.getName(),
                                        toJson(call.getArguments()))))
                                .build());
                    } else {
                        result.add(AssistantMessage.builder().content(content).build());
                    }
                }
                case TOOL -> result.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                message.getToolCall() == null ? "unknown" : message.getToolCall().getId(),
                                message.getToolCall() == null ? "unknown" : message.getToolCall().getName(),
                                message.getToolResult() == null ? content : message.getToolResult())))
                        .build());
            }
        }
        return result;
    }

    /** 由工具参数定义生成 JSON Schema，供模型理解工具签名。 */
    private String toJsonSchema(ToolDefinition definition) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        definition.getParameters().forEach((name, spec) -> {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", spec.getType() == null ? "string" : spec.getType());
            property.put("description", spec.getDescription() == null ? "" : spec.getDescription());
            properties.put(name, property);
            if (spec.isRequired()) {
                required.add(name);
            }
        });

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return toJson(schema);
    }

    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("[SpringAiLLMProvider] cannot parse tool arguments: {}", json);
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private int promptTokens(ChatResponse response) {
        return response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? 0 : nullSafe(response.getMetadata().getUsage().getPromptTokens());
    }

    private int completionTokens(ChatResponse response) {
        return response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? 0 : nullSafe(response.getMetadata().getUsage().getCompletionTokens());
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }
}
