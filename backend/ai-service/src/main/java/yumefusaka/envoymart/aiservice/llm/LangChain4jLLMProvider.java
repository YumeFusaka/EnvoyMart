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
import yumefusaka.envoymart.agent.llm.TokenLedger;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.loop.ToolContextKeys;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.PendingAction;
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
 * <b>工具循环是我们自己写的，护栏因此是循环里的局部变量。</b>LangChain4j 的
 * {@code ChatModel.chat()} 不执行工具：它把 tool_call 原样返回。所以
 * {@link #chatWithTools} 里那个 while 循环是我们写的，「循环最多转几圈、什么时候撤掉工具」
 * 也就全归我们判定 —— 这是 {@link yumefusaka.envoymart.agent.loop.LoopGuard} 能同时管住
 * 这个循环与执行图里 ACT → EVALUATE → REPLAN 那个环的前提。
 * <p>
 * <b>单次与带工具两条路径的分界线是硬的。</b>带工具的循环只有 {@link #chatWithTools} 走；
 * {@link #chat} 是单次调用，规划、意图分类、记忆抽取用它——它们只要一段文本或一个 JSON，
 * 下发工具定义只会让模型误选，而那条路上的 tool_call 没有任何人消费。
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
     * <p>
     * <b>护栏耗尽后要撤掉工具定义，而不是继续带着工具问。</b>这个循环原本唯一的出口是
     * 「模型这一轮没要工具」——被拦下的调用只是回填一条拒绝消息再转一圈，而下一圈
     * 模型照样能要工具。于是预算耗尽之后的终止，靠的是<b>模型看到拒绝提示后选择停手</b>：
     * 是模型配合，不是代码保证。一个不配合的模型，或者一次成功的提示注入
     * （「继续调用工具」），就能让它一直转下去，每圈都是一次真实计费的调用。
     * <p>
     * 现在的判据是护栏自己：它一旦耗尽，这一次问话就不带工具定义——模型想再要也没有，
     * 只能基于已有信息作答。循环的终结点因此回到代码手里。判据放在<b>每轮开头</b>而不是
     * 工具执行之后，是因为护栏是<b>一次请求一份</b>：图里靠前的节点把预算花光时，
     * 后面节点的第一次问话就该是不带工具的。
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
        int rounds = 0;
        int maxRounds = ctx.guard.maxToolCalls() + MAX_ROUND_SLACK;
        try {
            while (true) {
                response = chatModel.chat(buildRequest(working, config, toolsFor(ctx, specs)));
                rounds++;
                TokenUsage usage = response.tokenUsage();
                promptTokens += usageInt(usage, true);
                completionTokens += usageInt(usage, false);

                AiMessage aiMessage = response.aiMessage();
                if (!aiMessage.hasToolExecutionRequests()) {
                    break;
                }
                if (rounds >= maxRounds) {
                    log.warn("[LLM] 工具循环触到硬性轮次上限 model={} rounds={} {}",
                            config.getModel(), rounds, ctx.guard.summary());
                    break;
                }
                working.add(aiMessage);
                // 撞上未确认的高危操作：立即收口。此时还没拿到最终回答（这一轮整轮都在
                // 要工具），返回的 content 为空，由上层把回答换成确认提示
                if (executeToolRequests(aiMessage.toolExecutionRequests(), working, ctx, executions)) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            recordAbortedRound(config.getModel(), false, startedAt, rounds, promptTokens, completionTokens, executions);
            throw e;
        }
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

        return toLLMResponse(response, config, latencyMs, executions, promptTokens, completionTokens);
    }

    /**
     * 流式版本的 ReAct。
     * <p>
     * <b>只有最终回答会被推送。</b>工具轮里模型可能吐出的过渡文本不推给用户——
     * 调用方（{@code AgentGraph.answerNode}）会把收到的每个 chunk 累积成最终答案，
     * 推出中间文本会污染那个累积值，用户看到的就是「我先查一下……找到了……」拼上答案。
     * 代价是首字延迟只取决于最终回答的首个 token，而不是工具轮的快速吐字。
     */
    @Override
    public List<ToolExecution> chatStreamWithTools(List<ChatMessage> messages, LLMConfig config,
                                                   Map<String, Object> toolContext, Consumer<String> onChunk) {
        if (streamingChatModel == null) {
            LLMResponse fallback = chatWithTools(messages, config, toolContext);
            if (fallback.getContent() != null) {
                onChunk.accept(fallback.getContent());
            }
            return fallback.getToolExecutions() == null ? List.of() : fallback.getToolExecutions();
        }

        List<ToolExecution> executions = new ArrayList<>();
        LoopContext ctx = LoopContext.from(toolContext);
        List<dev.langchain4j.data.message.ChatMessage> working = toLangChainMessages(messages);
        List<ToolSpecification> specs = toToolSpecifications();

        long startedAt = System.nanoTime();
        int rounds = 0;
        int maxRounds = ctx.guard.maxToolCalls() + MAX_ROUND_SLACK;
        // 跨轮累加：ReAct 每转一圈都是一次真实计费的调用，只报最后一轮等于漏掉前面每一圈。
        // 而非流式那条（chatWithTools）从一开始就是累加的——同一条循环，两条分支两种口径
        int promptTokens = 0;
        int completionTokens = 0;
        try {
            while (true) {
                // 同 chatWithTools：护栏耗尽后不再下发工具定义，这是循环的终止判据
                StreamedRound round = streamOneRound(working, config, toolsFor(ctx, specs));
                rounds++;
                promptTokens += round.promptTokens;
                completionTokens += round.completionTokens;

                if (!round.aiMessage.hasToolExecutionRequests()) {
                    // 最终回答：此时才把这一轮攒下的 chunk 推出去
                    round.chunks.forEach(onChunk);
                    long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
                    log.info("[LLM] stream+tools model={} latencyMs={} rounds={} chars={} "
                                    + "promptTokens={} completionTokens={} toolExecutions={}",
                            config.getModel(), latencyMs, rounds, round.totalChars(),
                            promptTokens, completionTokens, executions.size());
                    recordLlmMetrics(config.getModel(), true, latencyMs, promptTokens, completionTokens);
                    return List.copyOf(executions);
                }

                if (rounds >= maxRounds) {
                    log.warn("[LLM] 流式工具循环触到硬性轮次上限 model={} rounds={} {}",
                            config.getModel(), rounds, ctx.guard.summary());
                    // 走到这里说明最后一轮的正文是空的（它整轮都在要工具），一个字都没推过。
                    // 不补一句，前端就是一个空气泡——非流式那条分支有 AgentGraph 兜底把空正文
                    // 换成「抱歉，我没能完成这个请求」，流式这条没有，正文早就推完了
                    onChunk.accept("抱歉，这个问题涉及的操作步骤过多，我没能在限定轮次内完成。请换个说法或拆开再问一次。");
                    recordLlmMetrics(config.getModel(), true,
                            (System.nanoTime() - startedAt) / 1_000_000, promptTokens, completionTokens);
                    return List.copyOf(executions);
                }

                working.add(round.aiMessage);
                if (executeToolRequests(round.aiMessage.toolExecutionRequests(), working, ctx, executions)) {
                    // 高危中断出口。这一轮整轮都在要工具、一个字都没推过，所以不推任何 chunk；
                    // 用量照记 —— 这里的每一圈都是真实计费的调用，漏记就是又一次静默漏账
                    long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
                    log.info("[LLM] stream+tools 高危中断 model={} latencyMs={} rounds={} "
                                    + "promptTokens={} completionTokens={} toolExecutions={}",
                            config.getModel(), latencyMs, rounds,
                            promptTokens, completionTokens, executions.size());
                    recordLlmMetrics(config.getModel(), true, latencyMs, promptTokens, completionTokens);
                    return List.copyOf(executions);
                }
            }
        } catch (RuntimeException e) {
            recordAbortedRound(config.getModel(), true, startedAt, rounds, promptTokens, completionTokens, executions);
            throw e;
        }
    }

    /**
     * 这一轮往返该不该带上工具定义。
     * <p>
     * 预算耗尽就撤掉工具，让循环有确定的出口。这是「循环一定会停」的<b>唯一</b>依据，
     * 所以它不能是某个可选参数：只要有一次问话还带着工具，模型就能再要一次。
     */
    private static List<ToolSpecification> toolsFor(LoopContext ctx, List<ToolSpecification> specs) {
        return ctx.guard.isExhausted() ? List.of() : specs;
    }

    /**
     * 硬性轮次上限比工具预算多留的余量。
     * <p>
     * 正常的出口是护栏：预算耗尽 → 撤掉工具定义 → 模型无事可要 → 循环结束。
     * 上限只兜一件事：<b>模型在没有工具定义时仍吐出一个 tool_call</b>。
     * 合规的 API 不该这样，但模型是不可信输入，而这种情况下护栏拦得住执行、
     * 拦不住往返 —— 每转一圈就是一次真实计费的调用。
     * <p>
     * 留 2 的余量是因为「最后一次问话」和「收口那一问」都不花工具预算：
     * 预算 N 次工具，正常路径正好是 N 次往返 + 1 次收口。
     */
    private static final int MAX_ROUND_SLACK = 2;

    /**
     * 执行模型请求的这一批工具，把结果回填进消息列表。
     * <p>
     * 三件事由我们把关：<b>护栏</b>（超预算或重复调用时拒绝执行，把原因交回给模型）、
     * <b>身份</b>（只认认证结果，绝不从模型给的参数里取），以及<b>高危确认</b>
     * （未确认的高危操作拦在执行之前，与计划路径同一个位置）。
     *
     * @return true 表示这一批里撞上了未确认的高危操作，调用方的循环必须<b>立即中断</b>
     */
    private boolean executeToolRequests(List<ToolExecutionRequest> requests,
                                        List<dev.langchain4j.data.message.ChatMessage> working,
                                        LoopContext ctx, List<ToolExecution> sink) {
        for (ToolExecutionRequest request : requests) {
            Map<String, Object> arguments = parseArguments(request.arguments());

            if (!ctx.guard.allowToolCall(request.name(), arguments)) {
                log.warn("[Tool] {} blocked by loop guard: {}", request.name(), ctx.guard.getStopReason());
                toolRegistry.recordBlocked(request.name());
                working.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(
                        request, ctx.guard.getStopReason() + "。请基于已有信息作答，不要再调用工具。"));
                continue;
            }

            // 高危拦截：与计划路径同一位置——调用之前。计划路径能提前看到整份计划、
            // 在批次执行前拦；ReAct 无从预知模型要调什么，只能在它调出来之后、执行之前拦。
            //
            // 拦下即中断整条循环，不做两件事：
            //  ① 不执行这一批剩余的工具——它们可能依赖被拦操作的结果；
            //  ② 不把拒绝回填给模型让它绕路——被拦的是「用户还没批准」，不是「模型想错了」，
            //     转一圈它只会换个说法再要一次，每一圈都是一次真实计费的调用。
            // 载荷写进 sink 交给调用方：ReAct 路径的高危确认出口靠它签发确认令牌
            if (requiresConfirmation(request.name())) {
                ctx.pendingActions.add(PendingAction.of(request.name(), arguments));
                log.info("[LLM] 高危操作待用户确认，ReAct 循环中断 tool={}", request.name());
                return true;
            }

            // 身份只认认证结果。arguments 里的同名项无条件剔除——
            // 工具定义已经不声明 userId，但模型生成的参数是任意 JSON，留着就是一条旁路。
            arguments = new LinkedHashMap<>(arguments);
            arguments.remove(ToolContextKeys.USER_ID);

            // confirmed 恒为 false：走到这里的都是没被拦下的常规工具，本来就不需要批准。
            // 需要批准的那些上面已经返回了——放行只发生在确认轮，而确认轮不经这个循环
            ToolResult result = toolRegistry.execute(new ToolCall(
                    request.id() == null ? UUID.randomUUID().toString() : request.id(),
                    request.name(), arguments, false, ctx.userId));

            String output = result.isSuccess()
                    ? String.valueOf(result.getOutput())
                    : "工具执行失败: " + result.getErrorMessage();

            sink.add(ToolExecution.builder()
                    .tool(request.name())
                    .input(request.arguments())
                    .output(output)
                    .success(result.isSuccess())
                    .noData(result.isNoData())
                    .latencyMs(result.getLatencyMs())
                    .rawData(result.getRawData())
                    .build());

            log.debug("[Tool] {} success={} output={}", request.name(), result.isSuccess(), output);
            working.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(request, output));
        }
        return false;
    }

    private boolean requiresConfirmation(String toolName) {
        return toolRegistry.get(toolName)
                .map(tool -> tool.getDefinition().isRequiresConfirmation())
                .orElse(false);
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
        log.info("[LLM] stream model={} latencyMs={} chars={} promptTokens={} completionTokens={}",
                config.getModel(), latencyMs, round.totalChars(),
                round.promptTokens, round.completionTokens);
        // stream=true 与同步调用分开看，否则首字延迟会被整轮时长污染。
        // 用量取自流末的收尾帧（onCompleteResponse）：增量块不带用量，收尾这一份才是全的
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
                .map(LangChain4jLLMProvider::describeTool)
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
                                arguments 的键必须逐字照抄工具说明里列出的参数名，不要改写大小写、
                                不要把驼峰改成下划线——工具按声明名取参数，写错的键取不到值。
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

    /**
     * 规划提示词里的单个工具说明。
     * <p>
     * <b>必须列出参数名，这是修一个实测缺陷。</b>早先这里只拼了工具名与用途描述，
     * 模型从头到尾看不到参数叫什么，只能从中文描述里猜——<b>实测 6/6 把 {@code orderId}
     * 猜成了 {@code order_id}</b>。这不是模型抽风，是提示词没给足信息：它没得选，只能猜。
     * <p>
     * 猜错的后果落在执行期：工具按声明名取参数取到 {@code null}，报出来是一句
     * {@code NullPointerException}——一个不指向任何真实原因的报错。
     * <p>
     * 只有计划路径会犯这个错。ReAct 那条路的工具调用由框架从声明的 schema 生成，
     * 键名不会错——<b>同一个工具，两条路径一个对一个错</b>，所以这个缺陷长期只在这边显形。
     */
    private static String describeTool(ToolDefinition definition) {
        StringBuilder sb = new StringBuilder("- ").append(definition.getName())
                .append(": ").append(definition.getDescription());
        Map<String, ToolDefinition.ParameterSpec> parameters = definition.getParameters();
        if (parameters == null || parameters.isEmpty()) {
            return sb.toString();
        }
        sb.append("\n  参数：");
        parameters.forEach((name, spec) -> sb.append("\n    ").append(name)
                .append("（").append(spec.getType()).append(spec.isRequired() ? "，必填" : "，选填")
                .append("）").append(spec.getDescription() == null ? "" : spec.getDescription()));
        return sb.toString();
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
     * 工具循环中途失败时的补记。
     * <p>
     * 转了几圈才失败的循环，前面每一圈都是已经发生过、已经计过费的真实调用。异常直接往上抛时
     * 它们既不进账本也不进指标，而这一轮的检索侧开销（向量化、重排）照记不误——账面上会留下
     * 一个「比真实值小、且没有任何迹象」的数字。失败路径因此是唯一会静默漏账的出口，必须自己结账。
     * <p>
     * 顺带把已执行过的工具打成一行 warn：异常抛出去之后，那个轨迹列表就再没有人看得见了，
     * 而「高危操作到底执行没执行」只能从这一行里找。
     */
    private void recordAbortedRound(String model, boolean stream, long startedAt, int rounds,
                                    int promptTokens, int completionTokens, List<ToolExecution> executions) {
        if (promptTokens == 0 && completionTokens == 0) {
            return;
        }
        log.warn("[LLM] 工具循环中途失败，补记已完成 {} 轮的用量 model={} promptTokens={} completionTokens={} "
                        + "executedTools={}",
                rounds, model, promptTokens, completionTokens,
                executions.stream().map(execution -> execution.getTool()).toList());
        recordLlmMetrics(model, stream, (System.nanoTime() - startedAt) / 1_000_000,
                promptTokens, completionTokens);
    }

    /**
     * 模型调用的耗时与 token 指标。
     * <p>
     * 日志回答"这一次发生了什么"，指标回答"最近一周贵在哪"——模型是按 token 计费的，
     * 没有按模型的用量趋势就无从谈成本控制。
     */
    private void recordLlmMetrics(String model, boolean stream, long latencyMs,
                                  int promptTokens, int completionTokens) {
        // 账本在指标之前：这里的三个调用点是全部模型调用的唯一汇聚处
        // （chat / chatWithTools 走 toLLMResponse，两条流式各一处），
        // 记在这里等于一次覆盖四个入口；指标那边没配 registry 就整段跳过，
        // 而"这一轮花了多少"不该因为没有监控就查不到
        TokenLedger.record(model, promptTokens, completionTokens);
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
     * 一次请求的循环上下文 —— 护栏、调用者身份，以及方向相反的一路：
     * 拦下的高危操作。
     * <p>
     * 迁到 LangChain4j 后前两样不再需要穿框架：循环就是我们自己写的，
     * 它们只是循环里的局部变量。这个类只是把解包做一次。
     * {@code pendingActions} 是出口——循环往里写，调用方（AgentGraph）读它决定中断。
     * <p>
     * 这里<b>没有</b>「用户已确认」这个开关，与 {@link ToolContextKeys} 保持一致：
     * 需要批准的操作在这一层只会被拦下，放行发生在确认轮，而确认轮压根不进这个循环。
     */
    private static final class LoopContext {
        private final LoopGuard guard;
        private final String userId;
        /** 拦下的高危操作载荷，见 {@link ToolContextKeys#PENDING_ACTIONS} */
        private final List<PendingAction> pendingActions;

        private LoopContext(LoopGuard guard, String userId, List<PendingAction> pendingActions) {
            this.guard = guard;
            this.userId = userId;
            this.pendingActions = pendingActions;
        }

        private static LoopContext from(Map<String, Object> toolContext) {
            String userId = (String) toolContext.get(ToolContextKeys.USER_ID);
            if (userId == null) {
                // MCP 路径：MCP Server 不携带我们的 toolContext，身份来自 McpAuthFilter 校验 JWT 后的结果。
                // 走 API Key（机器凭证、无用户身份）时这里仍为 null，需要身份的工具会 fail-closed。
                userId = BaseContext.getCurrentId();
            }
            LoopGuard guard = (LoopGuard) toolContext.get(ToolContextKeys.LOOP_GUARD);
            if (guard == null) {
                // 调用方没传护栏就地补一个。循环的终止条件是这一层自己的责任，
                // 不能取决于每个调用点都记得传 —— 漏传一次就是一次无上限的烧钱循环。
                // 传了护栏的调用方不受影响，两边用的是同一份预算。
                guard = new LoopGuard();
            }
            // 出口列表调用方不传就补一个本地的：拦截照常发生、载荷照常记录，
            // 只是没有调用方读得到 —— 与护栏的兜底同一个道理，责任在这一层
            Object providedSink = toolContext.get(ToolContextKeys.PENDING_ACTIONS);
            @SuppressWarnings("unchecked")
            List<PendingAction> pendingActions = providedSink instanceof List<?> provided
                    ? (List<PendingAction>) provided
                    : new ArrayList<>();
            return new LoopContext(guard, userId, pendingActions);
        }
    }
}
