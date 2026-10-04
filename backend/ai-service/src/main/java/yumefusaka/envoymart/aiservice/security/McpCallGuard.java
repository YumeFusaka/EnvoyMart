package yumefusaka.envoymart.aiservice.security;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.rag.TextTokenizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * MCP 工具调用的旁路护栏 —— 按调用方身份限流，并识别「原地打转」的振荡调用。
 * <p>
 * <b>为什么 MCP 这条路必须有自己的一份护栏</b>：Agent 内部那条路（{@code LoopGuard}）
 * 的前提是「循环由我们驱动」——预算掌握在我们手里，耗尽即撤掉工具定义。
 * MCP 的循环在<b>外部 Agent 那一侧</b>，一次请求里调到第几次完全不由我们决定；
 * 我们唯一看得到的是「一次连接、一次调用」，所以只能按时间窗口对<b>调用方</b>限流。
 * 这就是「护栏的边界必须跟着循环的归属走」。
 * <p>
 * <b>为什么不复用 agent-core 的 {@code LoopGuard}</b>：它是「一次请求一份」的有状态对象，
 * 构造在 Agent 装配里；复用需要为每个 MCP 调用方各持一份，并引入 agent-core 的
 * 装配与生命周期概念。MCP 侧要的只是「按身份 + 时间窗计数 + 交替模式识别」这一个
 * 局部不变量，硬造耦合换来的复用是负收益。两者共享的是<b>思路</b>，不是代码。
 * <p>
 * <b>为什么旁路而不是拒绝</b>：现在外部 Agent 拿着 API Key 无限打，
 * 先按「观测 + 有界拒绝」处理——超限才拒、振荡才拒，都不改协议契约（不新增参数、
 * 不改工具签名）。先加不破。
 * <p>
 * <b>高危确认的授权不在这里</b>：原先无令牌的 {@code confirmed=true} 只记告警、仍放行，
 * 那是「把确认升级为服务端签名令牌」之前的一步度量。升级已经完成——授权判定搬到了
 * {@code McpServerConfig.callHandler}：高危工具必须携带服务端签发的确认令牌，
 * 自填的 {@code confirmed} 不再构成授权。本类的 {@link #recordUnverifiedConfirmation}
 * 随之从「度量」变成「拒绝计数」：调用它的唯一位置是「令牌校验没通过」那条分支。
 */
@Slf4j
public class McpCallGuard {

    /** 一次调用放不放行，以及不放行的原因。 */
    public enum Verdict {
        ALLOWED,
        /** 该身份在本窗口内的调用次数已用满 */
        QUOTA_EXCEEDED,
        /** 与上一步在语义上等价的两个调用交替出现，判定为原地打转 */
        OSCILLATING
    }

    /**
     * 单个调用方每 60 秒允许的调用次数。
     * <p>
     * <b>取值依据</b>：这是「外部 Agent 的一个完整任务」的用量口径——内部对话链路
     * 一次请求的工具预算是 {@code LoopBudget.defaults() = 8} 次；外部 Agent 会自主多轮，
     * 单次任务按 2 倍余量给到 16；一分钟内正常调用方（一次查询、一次下单、一次追问）
     * 连 16 都不该到——要到这里，只能是外部代码在循环里重试。检测到振荡时会提前掐断，
     * 所以这个数字是「兜底上限」而不是「期望用量」。
     * <p>
     * 只对高危确认调用计数吗？不是：非高危工具单次也很便宜，但无限打一样会打满下游
     * （订单、物流、商品检索都是真接口），配额统一。
     */
    public static final int DEFAULT_QUOTA_PER_MINUTE = 16;

    /** 配额窗口长度：60 秒 */
    public static final long DEFAULT_WINDOW_MILLIS = 60_000L;

    /**
     * 振荡判定的最小历史步数。
     * <p>
     * 判据是「最近 4 步的调用签名 = A、B、A、B，且 A≠B」。取 2 个来回而不是 1 个
     * （A、B、A）——一次「换做法重试」会先返回 A 再试 B，那是一次合理切换，不该拦；
     * 只有它<b>又回到 A</b> 才说明两边都没往下走。4 步判据对应「两个方向各试了两次」。
     */
    public static final int MIN_WINDOW_SIZE = 4;

    /** 单次身份窗口内保留的最近调用签名数，取 8 是为了给振荡判定留余量又不无限增长 */
    private static final int MAX_SIGNATURE_HISTORY = 8;

    private final int quotaPerMinute;
    private final long windowMillis;
    /** 可注入的时钟，让单测不必真的等窗口过去 */
    private final LongSupplier clock;

    private final Map<String, PrincipalState> states = new ConcurrentHashMap<>();

    /** 无令牌的高危确认调用计数（只增不减，进程级度量） */
    private final AtomicLong unverifiedConfirmationCount = new AtomicLong();
    /** 被配额拒绝的调用计数 */
    private final AtomicLong quotaRejectionCount = new AtomicLong();
    /** 被振荡检测拒绝的调用计数 */
    private final AtomicLong oscillationRejectionCount = new AtomicLong();

    public McpCallGuard() {
        this(DEFAULT_QUOTA_PER_MINUTE, DEFAULT_WINDOW_MILLIS, System::currentTimeMillis);
    }

    public McpCallGuard(int quotaPerMinute, long windowMillis) {
        this(quotaPerMinute, windowMillis, System::currentTimeMillis);
    }

    McpCallGuard(int quotaPerMinute, long windowMillis, LongSupplier clock) {
        if (quotaPerMinute < 1 || windowMillis < 1) {
            throw new IllegalArgumentException("MCP 配额必须为正数");
        }
        this.quotaPerMinute = quotaPerMinute;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /**
     * 一次工具调用到达执行点前的准入判断；放行时同时把这次调用记进窗口。
     * <p>
     * 判据顺序是「振荡优先于配额」：振荡的日志要写出是哪两个调用在打转，
     * 而一旦已超配额，日志里只会剩「超额」，定位不到形态。
     *
     * @param principal 调用方身份（userId；API Key 客户端为 {@link McpAuthFilter#API_KEY_PRINCIPAL}）
     * @param toolName  工具名
     * @param arguments 工具参数（已剔除 userId / confirmed 这类协议层字段）
     */
    public Verdict check(String principal, String toolName, Map<String, Object> arguments) {
        String key = principal == null || principal.isBlank() ? "anonymous" : principal;
        PrincipalState state = states.computeIfAbsent(key, ignored -> new PrincipalState());
        synchronized (state) {
            long now = clock.getAsLong();
            // 固定窗口：跨窗口即整段重置。之所以不做滑动窗口，是因为要防的是
            // 「外部 Agent 打转」这种量级远超阈值的形态，差半个窗口的精度没有意义。
            //
            // 这一版没有「首次调用顺带重置一次」——窗口起点的选择无关正确性，
            // 但显式地分「首次」与「后续」，能让窗口边界只有一条推理路径。
            if (state.windowStart < 0) {
                state.windowStart = now;
            } else if (now - state.windowStart >= windowMillis) {
                state.windowStart = now;
                state.callsInWindow = 0;
            }

            String signature = signature(toolName, arguments);
            if (isOscillating(state.signatures, signature)) {
                oscillationRejectionCount.incrementAndGet();
                return Verdict.OSCILLATING;
            }

            if (state.callsInWindow >= quotaPerMinute) {
                quotaRejectionCount.incrementAndGet();
                return Verdict.QUOTA_EXCEEDED;
            }

            state.callsInWindow++;
            state.signatures.addLast(signature);
            while (state.signatures.size() > MAX_SIGNATURE_HISTORY) {
                state.signatures.removeFirst();
            }
            return Verdict.ALLOWED;
        }
    }

    /**
     * 记录一次「高危工具被确认、但确认只是调用方自填的布尔」。
     * <p>
     * <b>从度量改成了拒绝计数</b>：升级前它统计的是「有人裸填 true」，因为当时裸填被放行；
     * 现在裸填一律拒绝，它统计的是「被拒掉的高危调用」——签名无、令牌过期、令牌与用户
     * 对不上都会走到这里。计数只增不减，用来回答「有没有人在反复试探这个入口」。
     */
    public void recordUnverifiedConfirmation(String principal, String toolName) {
        long total = unverifiedConfirmationCount.incrementAndGet();
        log.warn("[MCP] 高危工具 {} 收到未经验证的 confirmed=true，调用方 {} —— "
                        + "当前协议只把 confirmed 当作调用方声明的契约，没有服务端凭证参与校验；累计 {} 次",
                toolName, principal == null || principal.isBlank() ? "anonymous" : principal, total);
    }

    public long unverifiedConfirmationCount() {
        return unverifiedConfirmationCount.get();
    }

    public long quotaRejectionCount() {
        return quotaRejectionCount.get();
    }

    public long oscillationRejectionCount() {
        return oscillationRejectionCount.get();
    }

    /** 供日志与诊断：某个身份当前窗口内已经用掉多少次。 */
    public int callsInCurrentWindow(String principal) {
        PrincipalState state = states.get(principal);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.callsInWindow;
        }
    }

    /**
     * 振荡判据：最近的调用序列是否呈 A、B、A、B 的交替形态。
     * <p>
     * 与 {@code LoopGuard} 的「同参重复」是两回事：同参重复看的是同一个签名出现几次，
     * 而振荡看的是<b>两个不同签名互相来回</b>——「查订单」与「查物流」交替出现，
     * 两个签名各自都只出现两次，按重复阈值永远拦不住，可它同样一步没往前走。
     * <p>
     * 只看交替形态、不看工具名集合，所以不会被「同一次任务里本来就该问几种工具」误伤——
     * 那种序列是 A、B、C 而不是 A、B、A、B。
     */
    private boolean isOscillating(Deque<String> history, String current) {
        List<String> recent = new ArrayList<>(history);
        recent.add(current);
        int size = recent.size();
        if (size < MIN_WINDOW_SIZE) {
            return false;
        }
        String a = recent.get(size - 4);
        String b = recent.get(size - 3);
        if (a.equals(b)) {
            return false;
        }
        return a.equals(recent.get(size - 2)) && b.equals(recent.get(size - 1));
    }

    /**
     * 调用签名 —— 工具名 + 参数的语义等价归一。
     * <p>
     * 先用 {@link TextTokenizer#tokenize} 而不是 {@code value.toString()}：
     * 后者在中文查询上会被「词序/标点/空格」的差异骗过——「乳清，蛋白粉」与
     * 「乳清蛋白粉」是同一次调用，但 toString 不同，于是真正的原地打转只要
     * <b>换个标点</b>就能绕过检测。
     * <p>
     * 归一前先<b>丢掉所有非字母数字字符</b>：分词器的二元组是「按连续字符段」切的，
     * 逗号会把「乳清蛋白粉」切成「乳清」与「蛋白粉」两段，产出「乳清 蛋白 白粉」，
     * 而带逗号的写法会多出「清蛋」这个跨标点二元组。这多出来的二元组来自标点本身，
     * 不是用户的语义，留着就等于「加个逗号就能逃过振荡检测」。
     */
    static String signature(String toolName, Map<String, Object> arguments) {
        Map<String, Object> safe = arguments == null ? Map.of() : arguments;
        Map<String, String> normalized = new HashMap<>();
        safe.forEach((name, value) -> normalized.put(name, normalizeValue(value)));
        // 字典序拼接，保证「同一个 arguments、不同的 Map 迭代顺序」得到同一个签名
        StringBuilder builder = new StringBuilder(toolName == null ? "" : toolName);
        normalized.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append('|').append(entry.getKey()).append('=')
                        .append(entry.getValue()));
        return builder.toString();
    }

    private static String normalizeValue(Object value) {
        if (value == null) {
            return "";
        }
        // 只保留字母与数字（含 CJK 表意文字——Character.isLetterOrDigit 对汉字为 true），
        // 其余（空格、中英标点、引号）一律丢弃：它们是写法的差异，不是调用的差异
        StringBuilder compact = new StringBuilder();
        String text = String.valueOf(value);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                compact.append(c);
            }
        }
        // 去重后的词元集合：重复出现的词不会因为次数不同而算出不同签名
        return String.join(" ", TextTokenizer.tokenize(compact.toString()));
    }

    /** 一个调用方的窗口状态；并发保护由 {@code check} 里的 synchronized(state) 承担。 */
    private static final class PrincipalState {
        /** 负数表示「这个身份还没开始第一个窗口」，起点在首次调用时按当前时钟确定 */
        private long windowStart = -1L;
        private int callsInWindow;
        private final Deque<String> signatures = new ArrayDeque<>();
    }
}
