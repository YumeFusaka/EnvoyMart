package yumefusaka.envoymart.aiservice.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.loop.ToolContextKeys;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.common.context.BaseContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * LangChain4j 接入层 —— 把 agent-core 的 LLMProvider 契约适配到 LangChain4j 的 ChatModel。
 * <p>
 * <b>与 Spring AI 版本的第一个区别是工具循环归谁跑。</b>Spring AI 2.0 把工具执行循环封在
 * {@code ChatClient} 的 advisor 链里，我们只能把护栏经 toolContext 下发、在 ToolCallback
 * 的调用点拦截；LangChain4j 的 {@code ChatModel.chat()} <b>根本不执行工具</b>，循环由调用方自己写。
 * 于是护栏回到了它本该在的位置——就是下面 {@link #runToolLoop} 里的局部变量。
 * <p>
 * <b>第二个区别是两条路径的分界线更硬。</b>带工具的循环只有 {@link #chatWithTools} 走；
 * {@link #chat} 是单次调用，规划、意图分类、记忆抽取用它——它们只要一段文本或一个 JSON，
 * 下发工具定义只会让模型误选。
 */
@Slf4j
public class LangChain4jLLMProvider implements LLMProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    /** 可为 null（未配置时），此时流式路径回退到非流式 */
    private final StreamingChatModel streamingChatModel;
    private final ToolRegistry toolRegistry;
    /** 全局默认调用配置（模型名等），规划这类内部调用也复用它 */
    private final LLMConfig defaultConfig;
    /** 模型调用的耗时与 token 走指标而不是只写日志——日志适合排查单次，指标才能看出趋势与成本 */
    private final MeterRegistry meterRegistry;

    public LangChain4jLLMProvider(ChatModel chatModel, StreamingChatModel streamingChatModel,
                                  ToolRegistry toolRegistry, LLMConfig defaultConfig,
                                  MeterRegistry meterRegistry) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.toolRegistry = toolRegistry;
        this.defaultConfig = defaultConfig;
        this.meterRegistry = meterRegistry;
    }

    // ==================== 单次调用（不驱动工具循环） ====================

    @Override
    public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
        return chat(messages, config, Map.of());
    }

    /**
     * 单次调用，<b>不下发工具定义</b>。
     * <p>
     * 规划、意图分类、记忆抽取走这里。早先无差别地把全部工具定义挂在每次调用上，
     * 这三类调用本只要一段文本或一个 JSON，却因此可能返回 tool_call——而单次调用
     * 路径上没有任何人消费它，等于白费一次调用。
     */
    @Override
    public LLMResponse chat(List<ChatMessage> messages, LLMConfig config, Map<String, Object> toolContext) {
        long startedAt = System.nanoTime();
        ChatResponse response = chatModel.chat(buildRequest(toLangChainMessages(messages), config, List.of()));
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        return toLLMResponse(response, config, latencyMs, List.of());
    }

    // ==================== 工具循环 ====================

    /**
     * ReAct 落点：完整的「模型返回 tool_call → 执行 → 回填结果 → 再问模型」往返。
     * <p>
     * 循环由本方法自己驱动，因此护栏、高危确认、调用者身份都是循环内的局部事实，
     * 不再需要经 toolContext 层层传递到某个回调里再解包。
     */
    @Override
    public LLMResponse chatWithTools(List<ChatMessage> messages, LLMConfig config,
                                     Map<String, Object> toolContext) {
        List<ToolExecution> executions = new ArrayList<>();
        LoopContext ctx = LoopContext.from(toolContext);
        List<dev.langchain4j.data.message.ChatMessage> working = toLangChainMessages(messages);
        List<ToolSpecification> specs = toToolSpecifications();

        long startedAt = System.nanoTime();
        int promptTokens = 0;
        int completionTokens = 0;
        ChatResponse response;
        while (true) {
            response = chatModel.chat(buildRequest(working, config, specs));
            TokenUsage usage = response.tokenUsage();
            promptTokens += usageInt(usage, true);
            completionTokens += usageInt(usage, false);

            AiMessage aiMessage = response.aiMessage();
            if (!aiMessage.hasToolExecutionRequests()) {
                break;
            }
            working.add(aiMessage);
            executeToolRequests(aiMessage.toolExecutionRequests(), working, ctx, executions);
        }
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

        return toLLMResponse(response, config, latencyMs, executions, promptTokens, completionTokens);
    }

    /**
     * 流式版本的 ReAct。
     * <p>
     * <b>只有最终回答会被推送。</b>工具轮里模型可能吐出的过渡文本不推给用户——
     * 调用方（{@code AgentGraph.converse}）会把收到的每个 chunk 累积成最终答案，
     * 推出中间文本会污染那个累积值。这也与迁移前的行为一致：Spring AI 的 advisor
     * 同样在工具往返期间不下发 chunk，首字延迟只取决于最终回答的首个 token。
     */
    @Override
    public void chatStreamWithTools(List<ChatMessage> messages, LLMConfig config,
                                    Map<String, Object> toolContext, Consumer<String> onChunk) {
        if (streamingChatModel == null) {
            LLMResponse fallback = chatWithTools(messages, config, toolContext);
            if (fallback.getContent() != null) {
                onChunk.accept(fallback.getContent());
            }
            return;
        }

        List<ToolExecution> executions = new ArrayList<>();
        LoopContext ctx = LoopContext.from(toolContext);
        List<dev.langchain4j.data.message.ChatMessage> working = toLangChainMessages(messages);
        List<ToolSpecification> specs = toToolSpecifications();

        long startedAt = System.nanoTime();
        int rounds = 0;
        while (true) {
            StreamedRound round = streamOneRound(working, config, specs);
            rounds++;

            if (!round.aiMessage.hasToolExecutionRequests()) {
                // 最终回答：此时才把这一轮攒下的 chunk 推出去
                round.chunks.forEach(onChunk);
                long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
                log.info("[LLM] stream+tools model={} latencyMs={} rounds={} chars={} toolExecutions={}",
                        config.getModel(), latencyMs, rounds, round.totalChars(), executions.size());
                recordLlmMetrics(config.getModel(), true, latencyMs,
                        round.promptTokens, round.completionTokens);
                return;
            }

            working.add(round.aiMessage);
            executeToolRequests(round.aiMessage.toolExecutionRequests(), working, ctx, executions);
        }
    }

    /**
     * 执行模型请求的这一批工具，把结果回填进消息列表。
     * <p>
     * 两件事由我们把关：<b>护栏</b>（超预算或重复调用时拒绝执行，把原因交回给模型）
     * 与<b>身份</b>（只认认证结果，绝不从模型给的参数里取）。
     */
    private void executeToolRequests(List<ToolExecutionRequest> requests,
                                     List<dev.langchain4j.data.message.ChatMessage> working,
                                     LoopContext ctx, List<ToolExecution> sink) {
        for (ToolExecutionRequest request : requests) {
            Map<String, Object> arguments = parseArguments(request.arguments());

            if (ctx.guard != null && !ctx.guard.allowToolCall(request.name(), arguments)) {
                log.warn("[Tool] {} blocked by loop guard: {}", request.name(), ctx.guard.getStopReason());
                toolRegistry.recordBlocked(request.name());
                working.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(
                        request, ctx.guard.getStopReason() + "。请基于已有信息作答，不要再调用工具。"));
                continue;
            }

            // 身份只认认证结果。arguments 里的同名项无条件剔除——
            // 工具定义已经不声明 userId，但模型生成的参数是任意 JSON，留着就是一条旁路。
            arguments = new LinkedHashMap<>(arguments);
            arguments.remove(ToolContextKeys.USER_ID);

            ToolResult result = toolRegistry.execute(new ToolCall(
                    request.id() == null ? UUID.randomUUID().toString() : request.id(),
                    request.name(), arguments, ctx.approved, ctx.userId));

            String output = result.isSuccess()
                    ? String.valueOf(result.getOutput())
                    : "工具执行失败: " + result.getErrorMessage();

            sink.add(ToolExecution.builder()
                    .tool(request.name())
                    .input(request.arguments())
                    .output(output)
                    .success(result.isSuccess())
                    .rawData(result.getRawData())
                    .build());

            log.debug("[Tool] {} success={} output={}", request.name(), result.isSuccess(), output);
            working.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(request, output));
        }
    }

    // ==================== 单次流式（不驱动工具循环） ====================

    @Override
    public void chatStream(List<ChatMessage> messages, LLMConfig config, Consumer<String> onChunk) {
        chatStream(messages, config, Map.of(), onChunk);
    }

    @Override
    public void chatStream(List<ChatMessage> messages, LLMConfig config,
                           Map<String, Object> toolContext, Consumer<String> onChunk) {
        if (streamingChatModel == null) {
            LLMResponse fallback = chat(messages, config, toolContext);
            if (fallback.getContent() != null) {
                onChunk.accept(fallback.getContent());
            }
            return;
        }

        long startedAt = System.nanoTime();
        StreamedRound round = streamOneRound(toLangChainMessages(messages), config, List.of());
        round.chunks.forEach(onChunk);

        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("[LLM] stream model={} latencyMs={} chars={}", config.getModel(), latencyMs, round.totalChars());
        // 流式拿不到增量 token 用量，这里只记耗时；stream=true 与同步调用分开看，
        // 否则首字延迟会被整轮时长污染
        recordLlmMetrics(config.getModel(), true, latencyMs, round.promptTokens, round.completionTokens);
    }

    /**
     * 跑一轮流式调用，把 chunk 攒起来交回调用方决定推不推。
     * <p>
     * 攒而不直接推，是因为「这一轮是不是最终回答」只有在轮次结束时才知道。
     */
    private StreamedRound streamOneRound(List<dev.langchain4j.data.message.ChatMessage> messages,
                                         LLMConfig config, List<ToolSpecification> specs) {
        StreamedRound round = new StreamedRound();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        streamingChatModel.chat(buildRequest(messages, config, specs), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                round.chunks.add(partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                round.aiMessage = response.aiMessage();
                TokenUsage usage = response.tokenUsage();
                round.promptTokens = usageInt(usage, true);
                round.completionTokens = usageInt(usage, false);
                done.countDown();
            }

            @Override
            public void onError(Throwable error) {
                failure.set(error);
                done.countDown();
            }
        });

        try {
            // 模型调用必须有超时：卡住的流会让调用方线程一直挂着
            if (!done.await(180, TimeUnit.SECONDS)) {
                throw new IllegalStateException("模型流式调用超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型流式调用被中断", e);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("模型流式调用失败: " + failure.get().getMessage(), failure.get());
        }
        if (round.aiMessage == null) {
            throw new IllegalStateException("模型流式调用未返回结果");
        }
        return round;
    }

    /** 一轮流式调用的中间状态。 */
    private static final class StreamedRound {
        private final List<String> chunks = new ArrayList<>();
        private AiMessage aiMessage;
        private int promptTokens;
        private int completionTokens;

        private int totalChars() {
            return chunks.stream().mapToInt(String::length).sum();
        }
    }

    // ==================== 规划 ====================

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
            log.warn("[LangChain4jLLMProvider] plan failed, fallback to rule-based: {}", e.getMessage());
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

    // ==================== 请求构造与结果映射 ====================

    /** per-request 的调用配置：模型名、温度、输出上限。连接级配置在模型实例上。 */
    private ChatRequest buildRequest(List<dev.langchain4j.data.message.ChatMessage> messages,
                                     LLMConfig config, List<ToolSpecification> specs) {
        ChatRequest.Builder builder = ChatRequest.builder()
                .messages(messages)
                .modelName(config.getModel() == null ? defaultConfig.getModel() : config.getModel())
                .temperature(config.getTemperature())
                .maxOutputTokens(config.getMaxTokens());
        if (!specs.isEmpty()) {
            builder.toolSpecifications(specs);
        }
        return builder.build();
    }

    private LLMResponse toLLMResponse(ChatResponse response, LLMConfig config, long latencyMs,
                                      List<ToolExecution> executions) {
        TokenUsage usage = response.tokenUsage();
        return toLLMResponse(response, config, latencyMs, executions,
                usageInt(usage, true), usageInt(usage, false));
    }

    private LLMResponse toLLMResponse(ChatResponse response, LLMConfig config, long latencyMs,
                                      List<ToolExecution> executions,
                                      int promptTokens, int completionTokens) {
        AiMessage output = response.aiMessage();
        List<ChatMessage.ToolCallRequest> toolCalls = output == null || output.toolExecutionRequests() == null
                ? List.of()
                : output.toolExecutionRequests().stream()
                .map(tc -> ChatMessage.ToolCallRequest.builder()
                        .id(tc.id())
                        .name(tc.name())
                        .arguments(parseArguments(tc.arguments()))
                        .build())
                .toList();

        // 成本可观测：每次模型调用的耗时、token 消耗与工具调用数
        log.info("[LLM] model={} latencyMs={} promptTokens={} completionTokens={} toolCalls={} toolExecutions={}",
                config.getModel(), latencyMs, promptTokens, completionTokens,
                toolCalls.size(), executions.size());
        recordLlmMetrics(config.getModel(), false, latencyMs, promptTokens, completionTokens);

        return LLMResponse.builder()
                .content(output == null ? null : output.text())
                .toolCalls(toolCalls)
                .toolExecutions(executions)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .finishReason(toolCalls.isEmpty()
                        ? LLMResponse.FinishReason.STOP
                        : LLMResponse.FinishReason.TOOL_CALL)
                .build();
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

    private int usageInt(TokenUsage usage, boolean input) {
        if (usage == null) {
            return 0;
        }
        Integer value = input ? usage.inputTokenCount() : usage.outputTokenCount();
        return value == null ? 0 : value;
    }

    /** 把 ToolRegistry 里的工具转成 LangChain4j 的工具规格，由模型理解签名。 */
    private List<ToolSpecification> toToolSpecifications() {
        return toolRegistry.listDefinitions().stream()
                .map(this::toToolSpecification)
                .toList();
    }

    /** 由工具参数定义生成 JSON Schema。 */
    private ToolSpecification toToolSpecification(ToolDefinition definition) {
        JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
        List<String> required = new ArrayList<>();
        definition.getParameters().forEach((name, spec) -> {
            String type = spec.getType() == null ? "string" : spec.getType();
            String description = spec.getDescription() == null ? "" : spec.getDescription();
            switch (type) {
                case "integer" -> schema.addIntegerProperty(name, description);
                case "number" -> schema.addNumberProperty(name, description);
                case "boolean" -> schema.addBooleanProperty(name, description);
                default -> schema.addStringProperty(name, description);
            }
            if (spec.isRequired()) {
                required.add(name);
            }
        });
        if (!required.isEmpty()) {
            schema.required(required);
        }
        return ToolSpecification.builder()
                .name(definition.getName())
                .description(definition.getDescription())
                .parameters(schema.build())
                .build();
    }

    private List<dev.langchain4j.data.message.ChatMessage> toLangChainMessages(List<ChatMessage> messages) {
        List<dev.langchain4j.data.message.ChatMessage> result = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            String content = message.getContent() == null ? "" : message.getContent();
            switch (message.getRole()) {
                case SYSTEM -> result.add(dev.langchain4j.data.message.SystemMessage.from(content));
                case USER -> result.add(dev.langchain4j.data.message.UserMessage.from(content));
                case ASSISTANT -> {
                    if (message.getToolCall() != null) {
                        ChatMessage.ToolCallRequest call = message.getToolCall();
                        result.add(AiMessage.from(content,
                                List.of(ToolExecutionRequest.builder()
                                        .id(call.getId())
                                        .name(call.getName())
                                        .arguments(toJson(call.getArguments()))
                                        .build())));
                    } else {
                        result.add(AiMessage.from(content));
                    }
                }
                case TOOL -> {
                    ChatMessage.ToolCallRequest call = message.getToolCall();
                    String text = message.getToolResult() == null ? content : message.getToolResult();
                    result.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(
                            call == null ? "unknown" : call.getId(),
                            call == null ? "unknown" : call.getName(),
                            text));
                }
            }
        }
        return result;
    }

    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("[LangChain4jLLMProvider] cannot parse tool arguments: {}", json);
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

    /**
     * 一次请求的循环上下文 —— 护栏、高危确认、调用者身份。
     * <p>
     * 迁到 LangChain4j 后这三样不再需要穿框架：循环就是我们自己写的，
     * 它们只是循环里的局部变量。这个类只是把解包做一次。
     */
    private static final class LoopContext {
        private final LoopGuard guard;
        private final boolean approved;
        private final String userId;

        private LoopContext(LoopGuard guard, boolean approved, String userId) {
            this.guard = guard;
            this.approved = approved;
            this.userId = userId;
        }

        private static LoopContext from(Map<String, Object> toolContext) {
            String userId = (String) toolContext.get(ToolContextKeys.USER_ID);
            if (userId == null) {
                // MCP 路径：MCP Server 不携带我们的 toolContext，身份来自 McpAuthFilter 校验 JWT 后的结果。
                // 走 API Key（机器凭证、无用户身份）时这里仍为 null，需要身份的工具会 fail-closed。
                userId = BaseContext.getCurrentId();
            }
            return new LoopContext(
                    (LoopGuard) toolContext.get(ToolContextKeys.LOOP_GUARD),
                    Boolean.TRUE.equals(toolContext.get(ToolContextKeys.APPROVED)),
                    userId);
        }
    }
}
