package yumefusaka.envoymart.agent.loop;

import lombok.Getter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 循环护栏 —— 一次请求一份，管住 Agent 的两类循环。
 * <p>
 * 这里的设计要点是<b>统一</b>：
 * <ul>
 *   <li>执行图里的 ACT → EVALUATE → REPLAN 环，由节点直接调用；</li>
 *   <li>ReAct 的工具循环：由执行图把同一个护栏放进调用参数里的 per-request 上下文
 *       （{@code AgentGraph} 写入 {@link ToolContextKeys#LOOP_GUARD} 这个键，
 *       {@code LangChain4jLLMProvider.chatWithTools(...)} 收到后解成 {@code LoopContext}），
 *       在 provider 自己的循环体里读出来判定。
 *       <b>护栏是循环体内的一个局部判断，不是包在工具外的装饰器</b>——
 *       {@code ChatModel.chat()} 那次迁移之后工具循环是框架自驱的，
 *       可拦截的位置只有循环体内部这一处。</li>
 * </ul>
 * 两处循环都在我们自己的代码里，<b>循环的边界与终止条件因此都归我们</b>——
 * 两种循环的"最多花多少"是同一套账。
 * <p>
 * <b>{@link #isExhausted()} 是驱动循环那一层的终止判据。</b>它不只是"拒绝某次调用"：
 * 工具循环在每轮开头读它，耗尽后就不再下发工具定义。只拦行动不拦循环的话，
 * 被拒的调用只是回填一条消息再转一圈，循环照样烧钱。
 * <p>
 * 但驱动层不能只依赖它。撤掉工具定义之后，<b>模型仍有可能吐出一个 tool_call</b>——
 * 合规的 API 不该这样，可模型是不可信输入，而它一旦这么做，护栏拦得住执行、拦不住往返，
 * 循环就又开始空转。所以驱动层还要有一条与模型无关的硬上限，
 * 见 {@link #maxToolCalls()}。
 * <p>
 * 护栏管三类浪费，三者根因不同、修法也不同：
 * <ul>
 *   <li><b>总预算</b>——花得太多；</li>
 *   <li><b>同参重复</b>——同一件事问两遍（签名相同）；</li>
 *   <li><b>往复震荡</b>——A→B→A→B 交替打转。它的每一步签名两两不同，
 *       总预算与同参重复一次也拦不住，只有交替模式检测能认出它。</li>
 * </ul>
 */
public class LoopGuard {

    /**
     * 参数签名用的 JSON 序列化器。
     * <p>
     * 用<b>规范化 JSON</b> 而不是「字典序拼串」是因为后者的等价类划错了：
     * <ul>
     *   <li>嵌套 {@code Map}/{@code Set} 的遍历顺序会进签名——{@code {a:{x:1,y:2}}} 与
     *       {@code {a:{y:2,x:1}}} 判成两次不同调用；</li>
     *   <li>{@code 3}（Integer）、{@code 3L}（Long）、{@code 3.0}（Double）的 {@code toString}
     *       不同，但它们经 JSON 解析后本就是同一个数值；</li>
     *   <li>空 {@code Map} 与 {@code null} 参数都落到 {@code ""}，两回事被当成一回事。</li>
     * </ul>
     * 这三条都只会让「同一操作」被漏判，从而漏掉该拦的重复调用。JSON 把值先归一成
     * 数字/字符串/布尔/null 再输出，{@code ORDER_MAP_ENTRIES_BY_KEYS} 再保证键序无关。
     * <p>
     * <b>刻意不开启 {@code JsonInclude.Include.NON_NULL}</b>：null 必须显式打印成 {@code null}，
     * 否则「缺字段」与「字段为 null」会被合并，同样丢区分度。
     * <p>
     * 它<b>不</b>把 {@code 3} 与 {@code "3"} 归一——JSON 里数字与字符串本是两种类型，
     * 把它们合并反而会让「按 id 查」和「按名字查」互相算重复。等价类只往「同一个值」收，
     * 不往「看起来一样」收。
     */
    private static final JsonMapper SIGNATURE_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    /**
     * 震荡检测的观察窗口长度。
     * <p>
     * 要认出「A→B→A→B→A→B」至少要看 6 次调用，因此取 6；这也让「先查订单、再查商品、
     * 又查订单」这种松散的合理交叉（窗口里 A 只出现两次且不相邻）不会被误伤。
     */
    private static final int OSCILLATION_WINDOW = 6;

    /**
     * 判定震荡所需的「相邻两步重复」次数。
     * <p>
     * 语义是「窗口内出现了至少两对 {@code (X,Y)} 相邻对，且这两对是同一组」。
     * 两次往复（A,B,A,B）正好构成两对相邻对 {@code (A,B)} 与 {@code (A,B)}；
     * 三次往复（A,B,A,B,A,B）构成三对。要求 ≥ 2 才能让「单次交叉」（A,B,A）不算震荡。
     */
    private static final int MIN_ALTERNATIONS = 2;

    private final LoopBudget budget;
    private final Map<String, Integer> actionCounts = new ConcurrentHashMap<>();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger planRounds = new AtomicInteger();
    private final AtomicInteger oscillations = new AtomicInteger();

    /** 最近若干次成功放行的操作签名，用于识别往复模式。只在 synchronized 方法里读写。 */
    private final Deque<String> recentOps = new ArrayDeque<>();

    @Getter
    private volatile String stopReason;

    public LoopGuard() {
        this(LoopBudget.defaults());
    }

    public LoopGuard(LoopBudget budget) {
        this.budget = budget;
    }

    /**
     * 工具调用前的准入判断：检查总预算、重复调用与往复震荡。
     *
     * @return false 表示已超预算，调用方应拒绝执行并让模型基于已有信息作答
     */
    public synchronized boolean allowToolCall(String tool, Map<String, Object> arguments) {
        if (stopReason != null) {
            return false;
        }
        // 先判后加：被自己这一关拒掉的那一次不该计入已用预算。
        // 反过来的话，摘要里会印出「用了 9/8」这种读起来像超支的数字
        if (toolCalls.get() >= budget.maxToolCalls()) {
            stopReason = "工具调用总数已达上限 " + budget.maxToolCalls();
            return false;
        }
        toolCalls.incrementAndGet();
        String key = signature(tool, arguments);
        int times = actionCounts.merge(key, 1, Integer::sum);
        if (times > budget.maxRepeatedAction()) {
            stopReason = "同一操作重复调用已达上限（" + tool + "）";
            return false;
        }
        // 震荡判定必须在重复判定之后：先让「同参第三次」给出更准确的停止原因，
        // 否则一条 A→B→A→B 里当 A 第三次出现时会被报成震荡，掩盖掉它同时也重复了
        if (isOscillating(key)) {
            stopReason = "检测到往复调用（" + tool + " ↔ 交替的另一步），已停止，请基于已有信息作答";
            return false;
        }
        recentOps.addLast(key);
        if (recentOps.size() > OSCILLATION_WINDOW) {
            recentOps.removeFirst();
        }
        return true;
    }

    /** 进入新一轮规划前的准入判断。 */
    public synchronized boolean allowPlanRound() {
        if (stopReason != null) {
            return false;
        }
        if (planRounds.get() >= budget.maxPlanRounds()) {
            stopReason = "规划轮次已达上限 " + budget.maxPlanRounds();
            return false;
        }
        planRounds.incrementAndGet();
        return true;
    }

    /** 护栏是否已经判定停止。驱动循环的那一层读它来决定还下不下发工具定义。 */
    public boolean isExhausted() {
        return stopReason != null;
    }

    /**
     * 工具调用预算。驱动循环的那一层用它推出「最多问模型几次」的硬上限 ——
     * 正常出口是 {@link #isExhausted()}，这条是模型不配合时的兜底。
     */
    public int maxToolCalls() {
        return budget.maxToolCalls();
    }

    public int toolCalls() {
        return toolCalls.get();
    }

    public int planRounds() {
        return planRounds.get();
    }

    /** 已判定出的往复次数（供日志与可观测）。 */
    public int oscillations() {
        return oscillations.get();
    }

    /** 用于日志与可观测：一次请求的循环消耗摘要。 */
    public String summary() {
        return "toolCalls=" + toolCalls.get() + "/" + budget.maxToolCalls()
                + " planRounds=" + planRounds.get() + "/" + budget.maxPlanRounds()
                + " oscillations=" + oscillations.get() + "/" + budget.maxOscillations()
                + (stopReason == null ? "" : " stoppedBy=[" + stopReason + "]");
    }

    /**
     * 最近窗口内是否出现「同一组相邻对重复 ≥ {@value #MIN_ALTERNATIONS} 次」，
     * 即 opX → opY → opX → opY。
     * <p>
     * 判定方式是把<b>当前这次调用</b>先追加进窗口（一份不会落库的视图），再把相邻对
     * {@code (w[i], w[i+1])} 数一遍：同一个 {@code (X,Y)} 出现几次就是几次往复。
     * 用「相邻对计数」而不是「上一步」状态，是因为它天然只在真正的交替模式上命中——
     * {@code A,A,A} 的相邻对是 {@code (A,A)} 重复，会被重复判定先拦掉；
     * {@code A,B,A}（单次交叉）只有一对，不到阈值，不误伤。
     * <p>
     * <b>参数语义等价才算同一个 op</b>：比较的是传进来的 {@code key}（工具 + 规范化参数），
     * 所以「同一个工具换参数」不会误判成打转。
     * <p>
     * 默认 {@code maxOscillations=2} 时，第 2 次往复（A,B,A,B，两种相邻对各两对）
     * 不超上限、放行；同一种相邻对出现第 3 次才停。
     */
    private boolean isOscillating(String currentKey) {
        if (budget.maxOscillations() <= 0) {
            return false;
        }
        List<String> window = new ArrayList<>(recentOps.size() + 1);
        window.addAll(recentOps);
        window.add(currentKey);
        // 统计相邻对 (w[i], w[i+1]) 的出现次数，取最大重复数
        int maxPair = 0;
        for (int i = 0; i + 1 < window.size(); i++) {
            int count = 0;
            for (int j = 0; j + 1 < window.size(); j++) {
                if (window.get(j).equals(window.get(i))
                        && window.get(j + 1).equals(window.get(i + 1))) {
                    count++;
                }
            }
            maxPair = Math.max(maxPair, count);
        }
        if (maxPair < MIN_ALTERNATIONS) {
            return false;
        }
        // 相邻对的重复次数就是「往复了几次」：两次往复（A,B,A,B）⇒ maxPair=2。
        // 上限语义是「允许几次往复之后停下」，所以严格大于才停：
        // maxOscillations=2 时第 2 次往复放行、第 3 次（A,B,A,B,A,B）拦下；
        // maxOscillations=1 时第 1 次往复放行、第 2 次拦下。
        return maxPair > budget.maxOscillations();
    }

    /**
     * 「工具 + 参数」的规范化签名。
     * <p>
     * 先序列化成 JSON，再递归把数值归一到 {@link BigDecimal#stripTrailingZeros()}——
     * 这样 {@code 3}、{@code 3L}、{@code 3.0} 得到同一个 {@code 3}，而字符串 {@code "3"}
     * 仍是 {@code "3"}、与数字保持区分。
     * <p>
     * 失败时退化成「工具名 + 参数数量」：刻意不返回空串，否则所有序列化异常的调用
     * 会共享一个签名、互相算作重复，把一个有回退价值的护栏变成随机拦截器。
     */
    private String signature(String tool, Map<String, Object> arguments) {
        if (arguments == null) {
            // null 参数与空 Map 是两回事，显式区分
            return tool + "|null";
        }
        try {
            JsonNode node = SIGNATURE_MAPPER.valueToTree(arguments);
            JsonNode normalized = normalizeNumbers(node);
            return tool + "|" + normalized.toString();
        } catch (RuntimeException e) {
            return tool + "|?size=" + arguments.size();
        }
    }

    /**
     * 递归把 JSON 树里的数值归一到 {@link BigDecimal#stripTrailingZeros()}。
     * <p>
     * 为什么必须在树上再做一遍：Jackson 的反序列化会保留字面量类型——{@code 3} 解析成
     * {@code IntNode}、{@code 3.0} 解析成 {@code DoubleNode}，两者的 {@code toString}
     * 分别是 {@code 3} 与 {@code 3.0}。但「整数 3」与「小数 3.0」在业务参数里是同一个值，
     * 模型在不同轮次把同一个 id 写成 {@code 3} 或 {@code 3.0} 完全可能，若不归一就会漏判重复。
     * <p>
     * 只归一 {@code isNumber} 的节点；字符串 {@code "3"} 不动，与数字保持类型区分。
     */
    private JsonNode normalizeNumbers(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(entry ->
                    out.set(entry.getKey(), normalizeNumbers(entry.getValue())));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : node) {
                out.add(normalizeNumbers(child));
            }
            return out;
        }
        if (node.isNumber()) {
            return JsonNodeFactory.instance.numberNode(
                    node.decimalValue().stripTrailingZeros());
        }
        return node;
    }
}
