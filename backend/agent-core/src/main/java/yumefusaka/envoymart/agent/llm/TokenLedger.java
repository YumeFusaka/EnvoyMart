package yumefusaka.envoymart.agent.llm;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一轮对话的 token 账本。
 * <p>
 * <b>为什么要有它。</b>一次用户提问在服务端不是一次模型调用：意图分类一次、计划一次、
 * ReAct 往返 N 次、收口合成一次，逢到记忆沉淀那些轮次还要再抽一次。日志里每一次都打得
 * 清清楚楚，但<b>没有任何一处知道这一整轮加起来是多少</b>——而用户问「这一问花了多少钱」
 * 问的正是那个总数。指标（Micrometer）那边是累计计数器，回答的是"最近一周贵在哪"，
 * 也不是这一问。
 * <p>
 * <b>为什么是环境式的（ThreadLocal）而不是一路传参。</b>模型调用的入口有四个
 * （{@code chat} / {@code chatWithTools} / {@code chatStream} / {@code chatStreamWithTools}），
 * 调用方散在编排层、记忆层与图谱构建里，其中 {@code plan} 那条连 {@code toolContext}
 * 都没有——逐个改签名，等于让每个中间层都拿着一个自己根本不用的参数往下递。
 * 账本是"这一轮发生的所有事"的横切关注点，就该按横切的方式挂。
 * <p>
 * <b>它靠什么成立，以及失效时是什么样。</b>整轮从进入 {@code AiAssistantServiceImpl}
 * 到返回都在同一个线程上跑（SSE 那条路是 {@code streamExecutor.submit} 起来的，整轮同步执行，
 * 没有把模型调用甩给别的线程），所以 ThreadLocal 装得下这一轮的全部用量。
 * 反过来说，<b>哪天有人把某次模型调用挪到另一个线程上，那一笔就会静默地不计入</b>——
 * 数字偏小、不报错。所以端到端验收里有一条是「账本总额必须等于该轮日志里各次调用之和」，
 * 而不是只看数字非零。
 *
 * @see #begin() 用法：{@code try (var scope = TokenLedger.begin()) { ... scope.snapshot() }}
 */
public final class TokenLedger {

    private static final ThreadLocal<TokenLedger> CURRENT = new ThreadLocal<>();

    /** 按模型分账：换模型只影响单价，拆开记才能既算钱又不必回头猜用的哪个模型 */
    private final Map<String, long[]> perModel = new ConcurrentHashMap<>();

    private TokenLedger() {
    }

    /**
     * 开账并绑定到当前线程。
     * <p>
     * 返回值必须关：它同时是取数与解绑的入口。用 try-with-resources 是为了让解绑
     * 不依赖任何一条 return 路径——这个账本活在线程池的线程上，
     * 漏一次解绑，下一个请求就会带着上一个请求的余额开盘。
     */
    public static Scope begin() {
        return new Scope(CURRENT.get());
    }

    /** 记账。没有开账时静默丢弃——图谱构建、离线批处理这些不该被硬塞一个账本 */
    public static void record(String model, int promptTokens, int completionTokens) {
        TokenLedger ledger = CURRENT.get();
        if (ledger == null || (promptTokens <= 0 && completionTokens <= 0)) {
            return;
        }
        long[] slot = ledger.perModel.computeIfAbsent(
                model == null ? "unknown" : model, key -> new long[2]);
        // 账本正常只被当前线程写；但流式回调、并行工具这些路径哪天改了线程模型，
        // 一次丢失的加法和一次丢失的钱是一样的，用并发容器把这条路直接封掉
        synchronized (slot) {
            slot[0] += promptTokens;
            slot[1] += completionTokens;
        }
    }

    private Snapshot snapshot() {
        List<ModelUsage> usages = perModel.entrySet().stream()
                .map(entry -> new ModelUsage(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .sorted(Comparator.comparing(ModelUsage::model))
                .toList();
        long prompt = usages.stream().mapToLong(ModelUsage::promptTokens).sum();
        long completion = usages.stream().mapToLong(ModelUsage::completionTokens).sum();
        return new Snapshot(usages, prompt, completion, prompt + completion);
    }

    /** 账本的存活范围。关掉即解绑，并<b>还原</b>外层账本——嵌套时内层不该把外层顶掉 */
    public static final class Scope implements AutoCloseable {
        private final TokenLedger previous;
        private final TokenLedger ledger;
        private Snapshot snapshot;
        private boolean closed;

        private Scope(TokenLedger previous) {
            this.previous = previous;
            this.ledger = new TokenLedger();
            CURRENT.set(ledger);
        }

        /**
         * 结账。可重复调用，返回同一个快照——收尾代码可能分散在正常路径与异常路径上，
         * 让第二次调用返回另一个数会制造两个不同的"总额"。
         * <p>
         * 关账之后仍可调用：返回的就是关账那一刻的数。收尾代码在 try 块外取数很自然，
         * 在那里抛 NPE 会显得像是账本坏了。
         */
        public Snapshot snapshot() {
            if (snapshot == null) {
                snapshot = ledger.snapshot();
            }
            return snapshot;
        }

        @Override
        public void close() {
            if (closed) {
                // 幂等。两次 close 之间可能有别的账本开在同一个线程上（收尾代码重复执行，
                // 或手动 close 之后又走了一遍 try-with-resources），再解一次就会把它顶掉，
                // 而之后所有记账都会记进一个已经没人看的账本里
                return;
            }
            // 先结账再解绑：快照缓存在 Scope 上，关账后取数拿到的仍是这一轮的值
            snapshot();
            if (previous == null) {
                // remove 而不是 set(null)：线程池的线程还要活很久，set(null) 会让这个
                // ThreadLocal 在这个线程上永远留一个键
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            closed = true;
        }
    }

    /** 一个模型的用量 */
    public record ModelUsage(String model, long promptTokens, long completionTokens) {
        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    /**
     * 一轮对话的用量总计。没有发生任何模型调用时四项皆为 0（或空列表），
     * 前端据此整块不渲染——显示一个「0 tokens」比不显示更糟，它看起来像统计坏了。
     */
    public record Snapshot(List<ModelUsage> models, long promptTokens, long completionTokens, long totalTokens) {
        public boolean isEmpty() {
            return totalTokens == 0;
        }
    }

    /** 当前线程是否在账本范围内——给「这一笔有没有被记上」这类自检用 */
    public static Optional<TokenLedger> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * 把当前账本显式带进另一个线程执行——与 {@code RequestId.inherit} 是同一件事的两个上下文：
     * 那边带的是日志标识，这边带的是这本账。
     * <p>
     * <b>为什么需要它。</b>账本按线程绑，而 agent 的并行计划步骤跑在
     * {@code agentExecutor} 的虚拟线程上（见 AiAgentConfig#agentExecutor）：检索与查询扩写
     * 都在那一步里发生，不显式带过去，这些调用就会<b>静默漏账</b>——数字偏小、不报错。
     * 实测漏过一次检索段的全部调用（约 1900 tokens/问），端到端对账断言当场抓住。
     * <p>
     * 执行完<b>还原目标线程原来的账本</b>而不是清掉：池化线程在任务之间复用，
     * 它可能正拿着自己那一轮的账——顶掉与留下同样糟。
     */
    public static Runnable inheriting(Runnable task) {
        TokenLedger captured = CURRENT.get();
        return () -> {
            TokenLedger previous = CURRENT.get();
            if (captured == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(captured);
            }
            try {
                task.run();
            } finally {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            }
        };
    }
}
