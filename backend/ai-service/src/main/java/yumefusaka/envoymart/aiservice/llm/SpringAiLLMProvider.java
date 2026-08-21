package yumefusaka.envoymart.aiservice.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spring AI 接入层 —— 把 agent-core 的 LLMProvider 契约适配到 Spring AI 的 ChatModel。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>模型接入、消息格式转换、工具定义下发由 Spring AI 负责；</li>
 *   <li>工具的实际执行仍走 agent-core 的 ToolRegistry，并在回调里记录调用轨迹；</li>
 *   <li>推理模式（ReAct / PAE）与上下文预算由 agent-core 编排层决定。</li>
 * </ul>
 */
@Slf4j
public class SpringAiLLMProvider implements LLMProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;

    public SpringAiLLMProvider(ChatModel chatModel, ToolRegistry toolRegistry) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
        List<ToolExecution> executions = new ArrayList<>();
        ChatOptions options = buildOptions(config, toToolCallbacks(executions));

        ChatResponse response = chatModel.call(new Prompt(toSpringMessages(messages), options));
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

        return LLMResponse.builder()
                .content(output.getText())
                .toolCalls(toolCalls)
                .toolExecutions(executions)
                .promptTokens(promptTokens(response))
                .completionTokens(completionTokens(response))
                .finishReason(toolCalls.isEmpty()
                        ? LLMResponse.FinishReason.STOP
                        : LLMResponse.FinishReason.TOOL_CALL)
                .build();
    }

    /**
     * 以模型自带的默认选项为模板改写，避免把通用 ChatOptions 强塞给具体模型实现
     * （OpenAI 等实现要求自己的 Options 类型）。
     */
    private ChatOptions buildOptions(LLMConfig config, List<ToolCallback> callbacks) {
        ChatOptions defaults = chatModel.getOptions();
        ChatOptions.Builder<?> builder = defaults == null ? ChatOptions.builder() : defaults.mutate();

        builder.model(config.getModel())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens());

        if (builder instanceof ToolCallingChatOptions.Builder<?> toolBuilder && !callbacks.isEmpty()) {
            toolBuilder.toolCallbacks(callbacks);
        }
        return builder.build();
    }

    @Override
    public List<PlanStep> plan(String userMessage, List<ToolDefinition> availableTools) {
        if (availableTools.isEmpty()) {
            return List.of();
        }

        String toolList = availableTools.stream()
                .map(d -> "- " + d.getName() + ": " + d.getDescription())
                .reduce("", (a, b) -> a + b + "\n");

        List<ChatMessage> messages = List.of(
                ChatMessage.builder().role(ChatMessage.Role.SYSTEM)
                        .content("""
                                你是电商客服任务规划器。根据用户请求和可用工具，输出一个 JSON 数组作为执行计划。
                                每个元素形如 {"tool":"工具名","arguments":{"参数名":"值"},"reason":"这一步要达成什么","optional":false}。
                                规则：只能使用下面列出的工具；不需要多步就返回只含一个元素的数组；无法完成则返回 []。
                                只输出 JSON，不要任何解释。

                                可用工具：
                                """ + toolList)
                        .build(),
                ChatMessage.builder().role(ChatMessage.Role.USER).content(userMessage).build()
        );

        try {
            LLMResponse response = chat(messages, LLMConfig.builder().temperature(0.0).build());
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
                    .build());
        }
        return steps;
    }

    /**
     * 把 ToolRegistry 里的工具包装成 Spring AI 的 ToolCallback。
     * 执行时仍然调用我们的 Tool，并记录轨迹供上层展示。
     */
    private List<ToolCallback> toToolCallbacks(List<ToolExecution> sink) {
        return toolRegistry.listDefinitions().stream()
                .map(def -> (ToolCallback) new RecordingToolCallback(def, sink))
                .toList();
    }

    private final class RecordingToolCallback implements ToolCallback {

        private final ToolDefinition definition;
        private final List<ToolExecution> sink;

        private RecordingToolCallback(ToolDefinition definition, List<ToolExecution> sink) {
            this.definition = definition;
            this.sink = sink;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(definition.getName())
                    .description(definition.getDescription())
                    .inputSchema(toJsonSchema(definition))
                    .build();
        }

        @Override
        public String call(String toolInput) {
            Map<String, Object> arguments = parseArguments(toolInput);
            ToolResult result = toolRegistry.execute(
                    new ToolCall(UUID.randomUUID().toString(), definition.getName(), arguments));

            String output = result.isSuccess()
                    ? String.valueOf(result.getOutput())
                    : "工具执行失败: " + result.getErrorMessage();

            sink.add(ToolExecution.builder()
                    .tool(definition.getName())
                    .input(toolInput)
                    .output(output)
                    .success(result.isSuccess())
                    .rawData(result.getRawData())
                    .build());

            return output;
        }
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
