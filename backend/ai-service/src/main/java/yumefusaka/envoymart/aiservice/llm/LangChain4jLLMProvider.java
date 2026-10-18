package yumefusaka.envoymart.aiservice.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.llm.StepReferences;
import yumefusaka.envoymart.agent.llm.TokenLedger;
import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.loop.ToolContextKeys;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.PendingAction;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolProgressListener;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.agent.tool.WriteFingerprints;
import yumefusaka.envoymart.common.context.BaseContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
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
    /**
     * 模型调用的 trace 通道（U13）。可为 null，此时所有观测退化为无操作——
     * 单测与无监控环境不需要额外装配分支。
     */
    private final ObservationRegistry observationRegistry;
    /**
     * 非流式模型调用的最长期限。LangChain4j 的 timeout 覆盖连接阶段，但<b>对端已连接却不返回响应</b>
     * 时 JDK HttpClient 会无限等待；图谱重建与规划这类调用一旦挂起，没有这层期限就会永久卡住
     * （索引永远 running、单个请求永远不返回）。超时后本次等待被放弃并转成可重试的失败。
     */
    private final long callDeadlineMs;
    /** 承载"带期限的模型调用"的虚拟线程池；挂起的对端不阻塞调用方线程。 */
    private final ExecutorService callDeadlineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public LangChain4jLLMProvider(ChatModel chatModel, StreamingChatModel streamingChatModel,
                                  ToolRegistry toolRegistry, LLMConfig defaultConfig,
                                  MeterRegistry meterRegistry) {
        this(chatModel, streamingChatModel, toolRegistry, defaultConfig, meterRegistry, null);
    }

    public LangChain4jLLMProvider(ChatModel chatModel, StreamingChatModel streamingChatModel,
                                  ToolRegistry toolRegistry, LLMConfig defaultConfig,
                                  MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this(chatModel, streamingChatModel, toolRegistry, defaultConfig, meterRegistry, observationRegistry, 60_000L);
    }

    public LangChain4jLLMProvider(ChatModel chatModel, StreamingChatModel streamingChatModel,
                                  ToolRegistry toolRegistry, LLMConfig defaultConfig,
                                  MeterRegistry meterRegistry, ObservationRegistry observationRegistry,
                                  long callDeadlineMs) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.toolRegistry = toolRegistry;
        this.defaultConfig = defaultConfig;
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
        this.callDeadlineMs = callDeadlineMs > 0 ? callDeadlineMs : 60_000L;
    }

    /**
     * 带期限的非流式调用：对端不响应时不无限等待，把挂起转成一次可被判定的失败。
     * 超时放弃等待后底层 socket 由虚拟线程继续持有直至对端关闭或操作系统回收，成本可忽略；
     * 关键是调用方能继续重试、降级或记录失败，而不是被一个请求拖住整条链路。
     */
    private ChatResponse chatWithDeadline(ChatRequest request) {
        CompletableFuture<ChatResponse> future =
                CompletableFuture.supplyAsync(() -> chatModel.chat(request), callDeadlineExecutor);
        try {
            return future.get(callDeadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            log.warn("[LLM] 模型调用超过 {}ms 未返回，放弃等待（对端不响应兜底）model={}", callDeadlineMs, defaultConfig.getModel());
            throw new IllegalStateException("模型调用超过 " + callDeadlineMs + "ms 未返回，已放弃等待");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("模型调用被中断");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        }
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
        ChatResponse response = chatWithDeadline(buildRequest(toLangChainMessages(messages), config, List.of()));
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
                // 取消在「下一次问话」之前生效：已经在跑的那一轮模型调用没有中断句柄，
                // 它的开销已经发生，只能让它跑完并如实记账；能控的是不再开始新的一轮
                ctx.progress.throwIfCancelled();
                response = chatWithDeadline(buildRequest(working, config, toolsFor(ctx, specs)));
                rounds++;
                TokenUsage usage = response.tokenUsage();
                promptTokens += usageInt(usage, true);
                completionTokens += usageInt(usage, false);

                // 取消在轮次期间到达：在途的那一轮没有中断句柄，只能让它跑完并如实记账
                // （用量上面已累加）；但它跑完的这一刻必须收手 —— 少了这一次检查，
                // 随后走的是「正常收尾」，一次用户按下的停止会被当成一轮完整作答交出去
                ctx.progress.throwIfCancelled();

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
     * <b>每个 chunk 产生即推送。</b>早先是「整轮攒完再一次性回放」：chunk 收在
     * {@code round.chunks} 里，等这一轮确认是最终回答才 forEach 出去。它带来的体感是
     * 生成期间界面一片静止，收尾瞬间倒出一整篇——恰好不是对话界面的样子。
     * 当时攒的理由是「工具轮的过渡文本不能推」，而真正的代价被算错了：
     * 过渡文本（「我先查一下您的订单」）推出去是<b>过程可见</b>，
     * 拦下来却是每一轮都让用户对着空屏等一个模型往返。
     * <p>
     * 现在推的就是模型的原始节奏：工具轮说了什么就显示什么（真实对话界面同样如此），
     * 调用方（{@code AgentGraph}）仍会把收到的每个 chunk 累积成最终答案——
     * 累积值 = 用户看到过的全文，历史与 done 帧因此和屏幕始终一致。
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
                // 取消在「下一次问话」之前生效，理由同 chatWithTools
                ctx.progress.throwIfCancelled();
                // 同 chatWithTools：护栏耗尽后不再下发工具定义，这是循环的终止判据
                StreamedRound round = streamOneRound(working, config, toolsFor(ctx, specs), onChunk);
                rounds++;
                promptTokens += round.promptTokens;
                completionTokens += round.completionTokens;

                // 取消在正文轮期间到达：这一轮是用户看到一半的那一轮，在途的字收不回来，
                // 但它跑完的这一刻必须收手。丢掉这一次检查，走的会是「正常收尾」——
                // 历史里写下整篇（对端早已断开，后半篇全推给了空气），而屏幕上只有半句，
                // 用户下次打开会话会发现回答「自己长长了」
                ctx.progress.throwIfCancelled();

                if (!round.aiMessage.hasToolExecutionRequests()) {
                    // 正文已随 chunk 逐字推走（见 streamOneRound），这里不再回放
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
            // 取消在「下一次工具执行」之前生效：这是取消语义里最硬的一段——
            // 工具是唯一会跨出本进程、产生不可撤销副作用的动作。已进入执行的那一次
            // 让它跑完（下游的写操作需要确定的结局），还没开始的绝不放行
            ctx.progress.throwIfCancelled();
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
                // 参数里还留着 $N 引用 = 它指向的值从来没被填进来。这种调用绝不能生成
                // 确认卡：卡片的全部意义是「用户批准的东西 = 真正会执行的东西」，
                // 而 $0.id 不是任何一次真实调用——用户点确认后必然失败，还以为系统坏了。
                //
                // <b>这道判据只有 ReAct 路径需要，而它此前完全没有。</b>计划路径的参数由
                // AgentGraph.resolveBatch 按前置步骤的真实输出填值，填不上就被上游阻断；
                // ReAct 的调用是模型直接给出的，没有「计划」可以预先解析——而 system prompt
                // 又确实教了模型 $N[i] 语法（多步任务的示例就写着 cart_add 用 "$0[0].skuId"），
                // 于是模型在 ReAct 轮里照写，字面量一路穿到确认卡。实测形态见 N1 文档发现 O。
                //
                // 与下面的「用户还没批准」不同，这一种**模型自己修得了**：它只是缺一次
                // 查询。所以这里回填一条可执行的纠正而不是中断——退回的是「先查真实编号
                // 再重发」，模型照做就能拿到一张正确的卡片。转圈有护栏兜底
                // （同参重复 + 震荡检测），不会变成无界重试。
                // 续作轮参数对账（与计划路径 executePlan 里那道同源）：这一步的操作目标
                // 若与已经落地过的写操作重合，同样不能生成确认卡。
                //
                // <b>为什么两条路径都要有。</b>计划路径能按「批次」提前比对；ReAct 的调用是
                // 模型直接给出的，没有批次——只能在它要出来之后、执行之前比。
                // 少了这一道，实测形态是同一件商品被加购两次：
                // 用户批准了 A，续作轮又把 A 填了一遍，而系统全程没有任何地方发现这是重复。
                String fingerprint = WriteFingerprints.of(request.name(), arguments);
                if (WriteFingerprints.isRepeat(fingerprint, ctx.executedWriteFingerprints)) {
                    log.warn("[LLM] 高危操作与已落地写操作重复，拒绝生成确认卡并退回模型 tool={} fingerprint={}",
                            request.name(), fingerprint);
                    working.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(request,
                            "这一步的操作目标（" + fingerprint + "）与已经执行过的写操作相同，"
                                    + "重复执行会产生用户没有要求的第二次副作用，因此没有生成确认卡片。"
                                    + "若本应作用于另一件商品、另一条记录或另一个订单，"
                                    + "请先用查询工具取得它真实的编号，再用真实值重新调用；"
                                    + "若用户要的就是刚才那一次，直接说明它已经执行过，不要再次发起。"));
                    continue;
                }
                if (StepReferences.hasUnresolved(arguments)) {
                    log.warn("[LLM] 高危操作参数含未解析引用，拒绝生成确认卡并退回模型 tool={} arguments={}",
                            request.name(), arguments);
                    working.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(request,
                            "参数里的 $0.id 这类步骤引用在当前调用中没有对应的步骤可以取值，"
                                    + "该语法只在执行计划里有效。请先用查询工具取得真实编号，"
                                    + "再用真实值重新调用 " + request.name() + "。"));
                    continue;
                }
                String operationId = "react:" + (request.id() == null ? UUID.randomUUID() : request.id());
                ctx.pendingActions.add(PendingAction.of(request.name(), arguments, operationId));
                log.info("[LLM] 高危操作待用户确认，ReAct 循环中断 tool={} operationId={}",
                        request.name(), operationId);
                return true;
            }

            // 身份只认认证结果。arguments 里的同名项无条件剔除——
            // 工具定义已经不声明 userId，但模型生成的参数是任意 JSON，留着就是一条旁路。
            arguments = new LinkedHashMap<>(arguments);
            arguments.remove(ToolContextKeys.USER_ID);

            // confirmed 恒为 false：走到这里的都是没被拦下的常规工具，本来就不需要批准。
            // 需要批准的那些上面已经返回了——放行只发生在确认轮，而确认轮不经这个循环
            ctx.progress.onStart(request.name());
            ToolResult result = toolRegistry.execute(new ToolCall(
                    request.id() == null ? UUID.randomUUID().toString() : request.id(),
                    request.name(), arguments, false, ctx.userId));
            ctx.progress.onFinish(request.name(), result.isSuccess(), result.isNoData(), result.getLatencyMs());

            String output = result.isSuccess()
                    ? String.valueOf(result.getOutput())
                    : "工具执行失败: " + result.getErrorMessage();

            sink.add(ToolExecution.builder()
                    .tool(request.name())
                    .operationId(request.id() == null ? null : "react:" + request.id())
                    .input(request.arguments())
                    .output(output)
                    .userFacingText(result.getUserFacingText() == null ? output : result.getUserFacingText())
                    .success(result.isSuccess())
                    .noData(result.isNoData())
                    .latencyMs(result.getLatencyMs())
                    .rawData(result.getRawData())
                    .evidence(result.getEvidence())
                    .facts(result.getFacts())
                    // 实体名必须一起带出去：引用校验靠它认商品行有没有出处，
                    // 少带这一个字段，ReAct 路径的商品表又会被整行删掉（P0-A）
                    .entities(result.getEntities())
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
        StreamedRound round = streamOneRound(toLangChainMessages(messages), config, List.of(), onChunk);

        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("[LLM] stream model={} latencyMs={} chars={} promptTokens={} completionTokens={}",
                config.getModel(), latencyMs, round.totalChars(),
                round.promptTokens, round.completionTokens);
        // stream=true 与同步调用分开看，否则首字延迟会被整轮时长污染。
        // 用量取自流末的收尾帧（onCompleteResponse）：增量块不带用量，收尾这一份才是全的
        recordLlmMetrics(config.getModel(), true, latencyMs, round.promptTokens, round.completionTokens);
    }

    /**
     * 跑一轮流式调用。chunk 边到边推（{@code live}），同时照旧攒一份留作轮次统计。
     * <p>
     * 推送发生在模型的回调线程上（HTTP 客户端的读线程），不换成队列转交工作线程：
     * 转交要给每个 chunk 付一次调度延迟，而 SSE 的 {@code SseEmitter} 本身
     * 对写入是加锁的，跨线程调用是它的设计场景。工作线程在下方用闩锁等待整轮结束，
     * 两侧通过 {@code CountDownLatch} 建立可见性，累积正文不会读到半个 chunk。
     */
    private StreamedRound streamOneRound(List<dev.langchain4j.data.message.ChatMessage> messages,
                                         LLMConfig config, List<ToolSpecification> specs,
                                         Consumer<String> live) {
        StreamedRound round = new StreamedRound();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        streamingChatModel.chat(buildRequest(messages, config, specs), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                round.chunks.add(partial);
                if (live != null) {
                    live.accept(partial);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                round.aiMessage = response.aiMessage();
                TokenUsage usage = response.tokenUsage();
                round.promptTokens = usageInt(usage, true);
                round.completionTokens = usageInt(usage, false);
                // 有正文而收尾帧没带用量：账记少了不会报错，只会让成本曲线悄悄偏低。
                // 这是「静默漏账」的唯一可观测点，明确留一行
                if (round.promptTokens == 0 && round.completionTokens == 0) {
                    log.warn("[LLM] 流式收尾帧未携带用量，本轮 token 记为 0 model={} chars={}",
                            config.getModel(), round.totalChars());
                }
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
        // 只取计划部分：既有调用方（评测、单测）不关心检索意图
        return planWithIntent(userMessage, availableTools, context).plan();
    }

    @Override
    public yumefusaka.envoymart.agent.llm.PlanWithIntent planWithIntent(
            String userMessage, List<ToolDefinition> availableTools, String context) {
        if (availableTools.isEmpty()) {
            // 没有可用工具时仍可能要检索：用户问「七天无理由怎么算」没有工具能答，
            // 答案在政策文档里。所以这里不能直接回 needRetrieval=false
            return new yumefusaka.envoymart.agent.llm.PlanWithIntent(List.of(), true);
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
                                不需要多步就返回只含一个元素的数组；只有**没有任何工具能推进任务**时才返回 []。
                                【缺地址不等于无法完成】用户要结算/下单但没给出收货信息、说地址没定、
                                或说“先不要用默认地址”时，不是无法完成——先计划 address_list 查询地址簿并列出让他挑；
                                只有地址簿为空，才请他提供完整收货信息。
                                【审批不是模型追问】工具标注“系统审批”时，规划/调用该工具只创建待审批操作，不会立即写入。
                                用户明确目标、规格与数量并说“等我确认”“等待确认”“确认后再加购”，
                                必须把相应写工具放进计划，由系统生成确认卡；不能只查询后口头询问，也不能返回空计划等回复。
                                用户说“先不加购/只看看”时不得计划加购；目标或多规格仍不明确时才澄清，不能猜SKU。
                                dependsOn 填「本步骤依赖的步骤序号」（从 0 开始）：
                                只有需要用到前面某一步的结果时才填，互不依赖的步骤留空数组，
                                这样它们会被并发执行。例如先查订单再取消，取消那步就要依赖查询步。
                                **当某一步的参数要用到前面步骤查出来的值时，写成
                                "$步骤序号.字段名"**，支持嵌套字段与列表下标，例如
                                "$0[1].skuId"、"$0[0].skus[1].skuId"、"$1.id"；执行器会用真实输出填值。
                                product_search 返回商品候选列表，每个候选有 skus 规格列表；
                                skus 中包含 skuId、specText、price、stock、purchasable。
                                仅商品只有一个规格时，候选顶层的 skuId 才有值，多规格时顶层 skuId 为空。
                                同一次搜索中的第一个商品用 "$0[0]"，第二个用 "$0[1]"，但引用必须继续指定字段；
                                选第一个商品的第二个规格用 "$0[0].skus[1].skuId"。
                                **product_search 的输出里已经印好了每条规格的可引用路径**，
                                形如 "$N[候选下标].skus[规格下标].skuId"：把 N 换成产出这条结果的步骤序号，
                                后面两段下标照抄即可，不要自己推算、也不要把「SKU16」这类展示编号写进引用。
                                用户未指定规格且有多个规格时，不得默认选第一项、不得猜编号，应询问用户选规格。
                                各层列表只有唯一元素时才允许省略该层下标；分别操作多个商品不能重复引用同一候选。
                                跨轮商品序号以背景里的“完整候选列表”为准；优先按商品编号精确 product_search，
                                商品搜索回答会由系统附上权威候选序号，请不要另排一份商品编号列表；说明/风险提示保留正常段落。
                                “第二个规格”是已选商品内部的规格序号，不是完整商品列表的第二个商品。
                                旧列表第2款查回后通常是本轮搜索的第0条，应引用 "$0[0]" 而不是沿用旧序号 "$0[1]"。
                                指定规格时按本轮结果 skus 的 specText 对应下标；两件商品要分别查询与分别绑定，不能复用同一SKU。
                                商品只有唯一SKU时，“单一规格/未标注规格文字”或空规格无需重复询问，使用真实唯一skuId；
                                多SKU而未选规格时仍必须澄清。取消旧待确认操作并改选商品时，只生成新目标的审批，不复用旧目标。
                                cart_query 返回当前购物车行列表，行 id 与 skuId 是不同编号；
                                cart_update / cart_remove 的 cartItemId 必须取目标行的 id，不能取 skuId 或展示序号。
                                用户说「第一行改2件」「删除第二行」时，本轮必须先 cart_query 取得当前行列表，
                                再分别引用 "$0[0].id" / "$0[1].id"，修改数量传 quantity=2。
                                $N 只指向本轮计划步骤，不能引用上一轮计划或历史回答中的第 N 步；
                                历史回答可帮助识别商品与规格，行 ID 缺失或状态不明时重新 cart_query，不猜 ID。
                                重新查询发现条目与历史所指不一致时，先澄清目标，不按新的序号悄悄替换旧目标。
                                订单查询返回的 id 是取消、物流等工具需要的数字内部 ID；orderNo 是用户可见的 YS 开头订单号，不能用于 order_cancel 的 orderId。
                                绝不要写「上一步的 XX」「来自第 0 步搜索结果的 XX」这类描述性文字——
                                它们不会被替换，会被原样当成参数传给工具，然后失败。
                                **引用的那个步骤必须真的写进计划里**：写 "$0.skuId" 就意味着
                                数组的第 0 个元素是把 skuId 查出来的那一步。只写引用它的那一步、
                                不写产出这个值的那一步，是错的——执行时没有第 0 步可以取值，
                                引用会原样传下去然后失败。用户说「先搜一下再把它加购」时，
                                计划应该有两个元素：先 product_search，再 cart_add(dependsOn:[0])。
                                例：先 product_search 查到商品、再 cart_add 加购，加购那步写
                                {"tool":"cart_add","arguments":{"skuId":"$0[0].skuId","quantity":1},
                                 "dependsOn":[0]}。
                                此例要求目标商品只有一个规格；用户已明确选第二个规格时改用 "$0[0].skus[1].skuId"。
                                arguments 的键必须逐字照抄工具说明里列出的参数名，不要改写大小写、
                                不要把驼峰改成下划线——工具按声明名取参数，写错的键取不到值。
                                只输出 JSON，不要任何解释。

                                另外，在同一份 JSON 里判断本轮是否需要先查平台知识库（说明书 / 政策 / 成分说明）。
                                输出格式改为一个对象：
                                {"plan":[...上面的数组...],"needRetrieval":true|false}。
                                needRetrieval 只在纯闲聊（「你好」「你是谁」）或纯业务操作
                                （加购 / 查订单 / 取消 / 退款，答案全在工具里、不需要知识库）时为 false；
                                其余一律为 true：商品咨询、成分与禁忌、规则与政策、健康与症状类提问，
                                答案的依据都在知识库里。拿不准时填 true——漏检索会让回答没有依据。

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
            return parsePlanWithIntent(response.getContent());
        } catch (Exception e) {
            log.warn("[LangChain4jLLMProvider] plan failed, fallback to rule-based: {}", e.getMessage());
            // 解析失败时退回「计划为空 + 需要检索」：这是最保守的一侧——
            // 少了检索会让模型没有依据，而多一次检索只是慢一点
            return new yumefusaka.envoymart.agent.llm.PlanWithIntent(List.of(), true);
        }
    }

    /**
     * 解析规划器的输出：既可能是新格式 {@code {"plan":[...],"needRetrieval":bool}}，
     * 也可能是旧格式的裸数组 {@code [...]}。
     * <p>
     * <b>两种都要认</b>：模型偶尔会忽略格式要求直接回数组，而评测夹具里也是裸数组。
     * 只认一种的话，另一种会被当成「解析失败 → 计划为空」，症状是「明明有工具却一步都没执行」。
     */
    @SuppressWarnings("unchecked")
    private yumefusaka.envoymart.agent.llm.PlanWithIntent parsePlanWithIntent(String raw) {
        if (raw == null || raw.isBlank()) {
            return new yumefusaka.envoymart.agent.llm.PlanWithIntent(List.of(), true);
        }
        // 输出可能是新格式的对象，也可能是旧格式的裸数组；两种都从第一个 JSON 起始符截到最后一个结束符，
        // 容忍模型在 JSON 前后附带的解释性文字
        int objStart = raw.indexOf('{');
        int arrStart = raw.indexOf('[');
        boolean asObject = objStart >= 0 && (arrStart < 0 || objStart < arrStart);
        int start = asObject ? objStart : arrStart;
        int end = asObject ? raw.lastIndexOf('}') : raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return new yumefusaka.envoymart.agent.llm.PlanWithIntent(List.of(), true);
        }
        String json = raw.substring(start, end + 1);
        try {
            if (asObject) {
                Object parsed = MAPPER.readValue(json, Object.class);
                if (!(parsed instanceof java.util.Map<?, ?> map)) {
                    return new yumefusaka.envoymart.agent.llm.PlanWithIntent(List.of(), true);
                }
                Object planNode = map.get("plan");
                String planJson = MAPPER.writeValueAsString(planNode == null ? List.of() : planNode);
                boolean needRetrieval = !Boolean.FALSE.equals(map.get("needRetrieval"));
                return new yumefusaka.envoymart.agent.llm.PlanWithIntent(parsePlan(planJson), needRetrieval);
            }
            // 裸数组：旧格式，按需要检索处理（与缺省一致）
            return new yumefusaka.envoymart.agent.llm.PlanWithIntent(parsePlan(json), true);
        } catch (Exception e) {
            log.warn("[LangChain4jLLMProvider] 解析规划输出失败，按「无计划 + 需要检索」处理：{}", e.getMessage());
            return new yumefusaka.envoymart.agent.llm.PlanWithIntent(List.of(), true);
        }
    }

    /**
     * 执行失败后的自我诊断：让模型先说清「为什么会失败」，再决定换什么策略。
     * <p>
     * <b>与 plan() 的分工</b>：plan() 产出可执行计划，本方法只产出<b>一句诊断</b>，
     * 两者各自的提示词只包含自己需要的信息——诊断要的是「失败的性质」，
     * 不需要工具签名，也不需要已知背景。合成一次调用既降低推理质量、又让
     * 「哪一步错了」的诊断被「下一步该调什么」的想法挤掉。
     * <p>
     * 温度取 0：诊断要稳定，同一段轨迹跑两次不该给出相反结论。
     *
     * @return 诊断句；模型调用失败时返回 null，调用方退回通用重规划上下文
     */
    @Override
    public String critique(String userMessage, String executionTrace,
                           List<yumefusaka.envoymart.agent.tool.ToolDefinition> availableTools) {
        List<ChatMessage> messages = List.of(
                ChatMessage.builder().role(ChatMessage.Role.SYSTEM)
                        .content("""
                                你是电商客服任务的失败诊断器。下面给出用户请求与已经执行过的步骤，
                                其中至少一步没有拿到可用结果。请用一句话说清失败的**性质**，并给出改法方向。
                                只输出这一句诊断，不要复述步骤，不要输出 JSON。

                                归类时必须从这四类里选一类，并说明理由：
                                - 「参数不当」：查询条件写窄了/写错了/用了数据里不存在的取值。
                                  改法是换个更宽或更常见的条件。
                                - 「工具选错」：该问题是另一个工具能答的。改法是指明换哪个工具。
                                - 「数据缺失」：平台数据里确实没有这条信息。改法是**停止重试**，
                                  如实告诉用户查不到，不要继续换词硬查。
                                - 「下游故障」：接口报错或超时。改法是稍后重试或降级说明。

                                区分「参数不当」和「数据缺失」是关键：前者还能救，后者必须承认。
                                数据里从来没有的东西，换十个说法也查不出来——把它说成「参数不当」
                                只会让系统继续空转。
                                """)
                        .build(),
                ChatMessage.builder().role(ChatMessage.Role.USER)
                        .content("用户请求：" + userMessage + "\n\n已执行步骤：\n" + executionTrace)
                        .build()
        );

        try {
            LLMConfig critiqueConfig = LLMConfig.builder()
                    .model(defaultConfig.getModel())
                    .temperature(0.0)
                    .maxTokens(256)
                    .build();
            LLMResponse response = chat(messages, critiqueConfig);
            String content = response.getContent();
            return content == null || content.isBlank() ? null : content.trim();
        } catch (Exception e) {
            // 诊断是给重规划加质量的一层，不是必经之路：它失败时退回通用重规划，
            // 而不是让整个请求失败——多花一次调用换更好的改法，不该换来更高的失败率
            log.warn("[LangChain4jLLMProvider] critique failed, fallback to generic replan: {}", e.getMessage());
            return null;
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
        if (definition.isRequiresConfirmation()) {
            sb.append("\n  系统审批：调用只生成待审批操作，用户通过签名确认后才执行；规划器不要用口头询问代替工具步骤。");
        }
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
            steps.add(PlanStep.builder()
                    // 缺工具名也必须保留为 null：不能在解析阶段删掉它，
                    // 否则后续步骤的 $N / dependsOn 下标会被重排，错误引用可能指向另一工具。
                    .tool(tool == null ? null : String.valueOf(tool))
                    .arguments((Map<String, Object>) item.getOrDefault("arguments", Map.of()))
                    .reason(String.valueOf(item.getOrDefault("reason", "")))
                    .optional(Boolean.TRUE.equals(item.get("optional")))
                    .dependsOn(parseDependsOn(item.get("dependsOn")))
                    .build());
        }
        return steps;
    }

    /**
     * 解析步骤依赖。
     * <p>
     * 保留原始下标，非法类型使用负数标记，由图的编译入口统一拒绝和修复。
     * 解析器不得删约束或截断小数，否则非法计划会绕过编译阶段的安全校验。
     */
    private List<Integer> parseDependsOn(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            return List.of(-1);
        }
        List<Integer> dependencies = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Number number)
                    || number.doubleValue() != number.longValue()
                    || number.longValue() > Integer.MAX_VALUE
                    || number.longValue() < Integer.MIN_VALUE) {
                dependencies.add(-1);
            } else {
                dependencies.add(number.intValue());
            }
        }
        return List.copyOf(dependencies);
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
        // trace 通道（U13）：一次模型调用 = 一段 Observation，挂在四个入口共同的汇聚点上。
        // 导出开关默认关，这里只是把 span 生成好——指标按模型聚合，回答不了"这一次为什么贵"，
        // 而 Observation 上的 model / token / 方向 正是复盘个案要的粒度。
        recordLlmTrace(model, stream, latencyMs, promptTokens, completionTokens);
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

    /**
     * 一次模型调用的 trace 观测（U13）。
     * <p>
     * observation 名统一 {@code model.chat}，方向用 tag 区分 sync/stream——
     * 名字里带上方向会让两边的聚合被割裂，而"同一个模型、两种调用方式"恰恰是要一起看的。
     * token 与工具数作为 attributes 挂上去，是为了在不导出（默认）时也能在
     * Observation 的 metrics 侧留下可以查的量。
     */
    private void recordLlmTrace(String model, boolean stream, long latencyMs,
                                int promptTokens, int completionTokens) {
        Observation observation = ModelTracing.start(observationRegistry, "model.chat");
        try {
            ModelTracing.lowCardinality(observation, "model", model);
            ModelTracing.lowCardinality(observation, "mode", stream ? "stream" : "sync");
            ModelTracing.highCardinality(observation, "promptTokens", String.valueOf(promptTokens));
            ModelTracing.highCardinality(observation, "completionTokens", String.valueOf(completionTokens));
            ModelTracing.highCardinality(observation, "latencyMs", String.valueOf(latencyMs));
        } finally {
            observation.stop();
        }
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
                // 数组必须带 items：只写 type=array 的 schema 有的模型直接拒收，
                // 有的会猜元素类型，而猜错要一路走到工具内部才炸
                case "array" -> schema.addProperty(name, JsonArraySchema.builder()
                        .description(description)
                        .items(JsonStringSchema.builder().build())
                        .build());
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
        /** 工具执行进度回调，见 {@link ToolContextKeys#TOOL_PROGRESS}；不传退化为 NOOP */
        private final ToolProgressListener progress;
        /** 已经落地过的写操作指纹，见 {@link ToolContextKeys#EXECUTED_WRITE_FINGERPRINTS}；不传退化为空 */
        private final List<String> executedWriteFingerprints;

        private LoopContext(LoopGuard guard, String userId, List<PendingAction> pendingActions,
                            ToolProgressListener progress, List<String> executedWriteFingerprints) {
            this.guard = guard;
            this.userId = userId;
            this.pendingActions = pendingActions;
            this.progress = progress;
            this.executedWriteFingerprints = executedWriteFingerprints;
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
            // 进度监听器同理：不传就退化为 NOOP（如 MCP 路径、非流式入口），
            // 界面少几片「正在执行」的指示，而不是在工具执行处抛 NPE
            ToolProgressListener progress = toolContext.get(ToolContextKeys.TOOL_PROGRESS)
                    instanceof ToolProgressListener listener ? listener : ToolProgressListener.NOOP;
            // 账本同理缺失即退化：不传就按「没有可对账的历史」处理，判重一律不命中
            Object providedLedger = toolContext.get(ToolContextKeys.EXECUTED_WRITE_FINGERPRINTS);
            @SuppressWarnings("unchecked")
            List<String> executedWriteFingerprints = providedLedger instanceof List<?> ledger
                    ? (List<String>) ledger
                    : List.of();
            return new LoopContext(guard, userId, pendingActions, progress, executedWriteFingerprints);
        }
    }
}
