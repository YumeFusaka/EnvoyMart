package yumefusaka.envoymart.agent.tool;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心 —— 以名称索引管理所有可用工具。
 */
@Slf4j
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    /** operationId 到一次真实结果的进程内缓存；跨服务仍由下游唯一键裁决。 */
    private final Map<String, ToolResult> operationResults = new ConcurrentHashMap<>();
    private final Map<String, Object> operationLocks = new ConcurrentHashMap<>();
    private final ToolCallListener listener;

    public ToolRegistry() {
        this(ToolCallListener.NOOP);
    }

    public ToolRegistry(ToolCallListener listener) {
        this.listener = listener == null ? ToolCallListener.NOOP : listener;
    }

    public void register(Tool tool) {
        if (tool == null || tool.getDefinition() == null
                || tool.getDefinition().getName() == null
                || tool.getDefinition().getName().isBlank()) {
            throw new IllegalArgumentException("工具定义必须包含非空 name");
        }
        tools.put(tool.getDefinition().getName(), tool);
    }

    public void registerAll(List<Tool> toolList) {
        toolList.forEach(this::register);
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> listDefinitions() {
        return tools.values().stream()
                .map(Tool::getDefinition)
                .toList();
    }

    /**
     * 启动装配后的契约校验。生产装配应显式调用；保留 register 的宽容性便于工具单测构造
     * 最小夹具。高危工具不能关闭幂等策略，否则确认重放无法证明不会重复副作用。
     */
    public void validateContracts() {
        tools.values().forEach(tool -> {
            ToolDefinition definition = tool.getDefinition();
            if (definition.isRequiresConfirmation()
                    && definition.getIdempotencyPolicy() == ToolDefinition.IdempotencyPolicy.NONE) {
                throw new IllegalStateException("高危工具未声明幂等策略：" + definition.getName());
            }
        });
    }

    public ToolResult execute(ToolCall call) {
        if (call == null || call.getToolName() == null || call.getToolName().isBlank()) {
            return ToolResult.builder().success(false).errorMessage("工具名不能为空").build();
        }
        Tool tool = tools.get(call.getToolName());
        if (tool == null) {
            ToolResult raw = ToolResult.builder().success(false)
                    .errorMessage("Tool not found: " + call.getToolName()).build();
            long latencyMs = 0;
            try {
                listener.onToolCall(call.getToolName(), ToolCallListener.Outcome.ERROR, latencyMs);
            } catch (RuntimeException e) {
                log.warn("[Tool] 调用监听器自身异常，不影响本次结果 tool={}", call.getToolName(), e);
            }
            return raw;
        }
        ToolDefinition definition = tool.getDefinition();
        String operationId = call.getOperationId();
        if (operationId != null && !operationId.isBlank()
                && definition.getIdempotencyPolicy() != ToolDefinition.IdempotencyPolicy.NONE) {
            String idempotencyKey = idempotencyKey(call);
            Object lock = operationLocks.computeIfAbsent(idempotencyKey, ignored -> new Object());
            synchronized (lock) {
                ToolResult previous = operationResults.get(idempotencyKey);
                if (previous != null) {
                    log.info("[Tool] operationId={} 重复提交，返回首次结果 tool={}", operationId, call.getToolName());
                    if (definition.getIdempotencyPolicy() == ToolDefinition.IdempotencyPolicy.NON_IDEMPOTENT) {
                        return previous.toBuilder().errorMessage("该操作已执行过，返回首次结果").build();
                    }
                    return previous;
                }
                ToolResult result = executeOnce(call, tool);
                // 未确认只是授权闸口的拒绝，确认后必须允许同一 operationId 继续执行；
                // 其余结果才作为第一次真实执行的权威回执缓存。
                // 瞬时故障不是权威业务结果：连接失败/超时后允许用同一 operationId
                // 查询或重试；只有已完成的成功或确定性业务结果才进入进程内幂等缓存。
                if (!result.isPendingApproval() && !result.isTransientFailure()) {
                    operationResults.put(idempotencyKey, result);
                }
                return result;
            }
        }
        return executeOnce(call, tool);
    }

    private ToolResult executeOnce(ToolCall call, Tool tool) {
        long startedAt = System.nanoTime();
        ToolResult raw = missingRequired(tool.getDefinition(), call);
        if (raw == null && tool.getDefinition().isRequiresConfirmation() && !call.isConfirmed()) {
            raw = ToolResult.builder().success(false).pendingApproval(true)
                    .errorMessage("该操作需要用户确认后才能执行：" + call.getToolName()).build();
        }
        if (raw == null) {
            try {
                raw = tool.execute(call);
            } catch (Exception e) {
                log.error("[Tool] {} 执行抛出异常", call.getToolName(), e);
                raw = ToolResult.builder().success(false)
                        .errorMessage("工具执行异常：" + e.getMessage()).build();
            }
        }

        // 三条来路（计划节点 / ReAct 循环 / MCP）都汇到这里，观测点放这儿才能全覆盖。
        // 观测不能影响被观测的行为：listener 一抛，工具本体已经执行完的结果就被丢掉，
        // 调用方看到的是失败、重试一次，一次取消/下单就被执行了两遍
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

        // 工具是这套系统里唯一「跨出本进程」的动作（Feign 调下游服务、MCP 调外部服务），
        // 而成功时它除了一个计数指标什么都不留。于是「回答不对」时的第一个问题——
        // 「这次到底调没调工具、调的是哪个」——只能靠翻模型侧的 tool_calls 反推。
        // 失败尤其要留：失败返回的是一句给模型看的话，它可能被模型转述、也可能被忽略，
        // 而这一行不会被谁转述走样
        // transient= 单列一个字段而不是并进 error 文案里：失败事后要靠日志分清
        // 「下游当时不可用」（重试/稍后再试有意义）和「这件事本身做不成」（重试无用），
        // 而两者的 errorMessage 都是一句人话，从文案上分不出来
        log.info("[Tool] {} success={} noData={} transient={} latencyMs={}{}", call.getToolName(),
                raw.isSuccess(), raw.isNoData(), raw.isTransientFailure(), latencyMs,
                raw.isSuccess() || raw.getErrorMessage() == null ? "" : " error=" + raw.getErrorMessage());
        try {
            listener.onToolCall(call.getToolName(),
                    raw.isSuccess() ? ToolCallListener.Outcome.SUCCESS : ToolCallListener.Outcome.ERROR,
                    latencyMs);
        } catch (RuntimeException e) {
            log.warn("[Tool] 调用监听器自身异常，不影响本次结果 tool={} : {}", call.getToolName(), e.toString());
        }
        return cap(raw.toBuilder().latencyMs(latencyMs).build());
    }

    private static String idempotencyKey(ToolCall call) {
        return String.valueOf(call.getUserId()) + "|" + call.getToolName() + "|"
                + call.getOperationId() + "|" + String.valueOf(call.getArguments());
    }

    /**
     * 单次工具输出的上限（字符）。
     * <p>
     * <b>工具的返回值是唯一一处「长度由业务数据决定、又被原样塞进模型上下文」的输入。</b>
     * 用户消息在入口有长度校验，知识片段按 512 字切分，画像 6 槽、情节 3 条——
     * 都是定量的。只有工具输出不是，而且不是一个工具如此：
     * <ul>
     *   <li>{@code logistics_query} 遍历运单的全部轨迹节点；</li>
     *   <li>{@code order_query} 遍历订单的全部明细行；</li>
     *   <li>{@code interaction_check} 遍历图谱命中的全部风险边（{@code risksOf} 侧刻意没有条数上限）。</li>
     * </ul>
     * 返回多少取决于这条运单有多少节点、这个用户下过多少单、这个成分在图上连了多少条边——
     * 都在系统的控制之外，而它每轮工具调用都要完整发一次。
     * 后果不是报错，是<b>成本随业务数据量静默增长</b>。
     * <p>
     * 4000 是「够用且封顶」的取法：正常规模下最长的是商品检索（10 条带卖点，1–2 KB），
     * 留了余量；再长多半是该加分页没加，那是工具自己该修的问题，
     * 截断至少让它在成本上不再失控。配合护栏的 8 次工具预算，单次请求的工具观测总量
     * 有确定上界。
     * <p>
     * <b>截断必须留痕</b>：不写「已截断」，模型会以为这就是全部，
     * 转而去分析一份半截的列表，最后给出一个基于残缺数据、却毫无保留的结论。
     * 结构化数据（{@code rawData}）不截断——它是给前端做二次加工的，
     * 不进模型上下文，也就没有撑爆成本的问题。
     * <p>
     * 放在这个出口而不是各工具里：注册中心是计划节点、ReAct 循环、MCP 三条来路的唯一汇合点，
     * 写一次三处都有。代价是 MCP 那条来路（外部客户端直接调用）也会被截断——
     * 它换来的是一句<b>不变的契约</b>：任何工具的输出都不超过这个长度，与走哪条路无关。
     */
    public static final int MAX_TOOL_OUTPUT_CHARS = 4000;

    private static ToolResult cap(ToolResult result) {
        String output = result.getOutput();
        if (output == null || output.length() <= MAX_TOOL_OUTPUT_CHARS) {
            return result;
        }
        String truncated = output.substring(0, MAX_TOOL_OUTPUT_CHARS)
                + "\n…（输出过长已截断，完整长度 " + output.length()
                + " 字符，以上仅为前 " + MAX_TOOL_OUTPUT_CHARS + " 字符）";
        return result.toBuilder().output(truncated).build();
    }

    /**
     * 必填参数校验：缺了就<b>当场说清缺哪个、实际收到什么</b>，而不是放进去让工具自己炸。
     * <p>
     * <b>模型的参数键是不可信输入。</b>计划路径的 arguments 由模型生成，实测会把
     * {@code orderId} 写成 {@code order_id}（提示词当时没列参数名，它只能猜）。
     * 工具按声明名取值取到 {@code null}，抛出来是一句 {@code NullPointerException}——
     * 既不指向原因，也不指向修法，只能靠人去读工具源码才明白是键名错了。
     * 这里挡一道，报错里同时给出「缺哪个」和「你给的是哪个」，一眼就能分清是键名写错还是漏传。
     * <p>
     * <b>校验放在这里而不是每个工具自己写</b>：计划节点、ReAct 循环、MCP 三条来路都汇到
     * {@link #execute}，写一次三处都有；散到各工具里则必然有的写了有的没写。
     * <p>
     * 只校验「必填项在不在」，不校验类型与取值——那是工具自己的事，注册中心不该越界。
     */
    private static ToolResult missingRequired(ToolDefinition definition, ToolCall call) {
        Map<String, ToolDefinition.ParameterSpec> declared = definition.getParameters();
        if (declared == null || declared.isEmpty()) {
            return null;
        }
        Map<String, Object> arguments = call.getArguments() == null ? Map.of() : call.getArguments();
        List<String> missing = declared.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().isRequired() && arguments.get(e.getKey()) == null)
                .map(Map.Entry::getKey)
                .toList();
        if (missing.isEmpty()) {
            return null;
        }
        return ToolResult.builder()
                .success(false)
                .errorMessage("缺少必填参数 " + String.join("、", missing)
                        + "；实际收到的参数是 " + (arguments.isEmpty() ? "（无）" : arguments.keySet()))
                .build();
    }

    /**
     * 记录一次"被护栏拦下"的尝试。
     * <p>
     * 它没有进入 {@link #execute}，不产生任何下游调用，但确实消费了一次预算——
     * 不单独计数的话，就无从判断"预算是设得过紧"还是"模型真在失控"。
     */
    public void recordBlocked(String toolName) {
        // 与 execute 出口同一个立场：观测不能影响被观测的行为。这里虽然只是计数，
        // 但监听器一抛会顺着调用方（护栏分支）冲进模型循环，把「被拦下」变成「整轮炸掉」
        try {
            listener.onToolCall(toolName, ToolCallListener.Outcome.BLOCKED, 0);
        } catch (RuntimeException e) {
            log.warn("[Tool] 调用监听器自身异常，不影响本次拦截 tool={} : {}", toolName, e.toString());
        }
    }

    /** 列出所有需要用户确认的高危工具名。 */
    public List<String> confirmationRequiredTools() {
        return tools.values().stream()
                .filter(tool -> tool.getDefinition().isRequiresConfirmation())
                .map(tool -> tool.getDefinition().getName())
                .toList();
    }
}
