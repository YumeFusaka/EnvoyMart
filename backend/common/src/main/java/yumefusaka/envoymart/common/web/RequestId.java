package yumefusaka.envoymart.common.web;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求标识 —— 把「这一次请求」在网关、各服务、服务间调用与异步线程里串成一条线。
 * <p>
 * <b>不做会怎样</b>：排查一次对话只能靠时间窗口 + sessionId 捞日志，而 sessionId 由客户端传、
 * 还可能被并发复用；跨服务的链路（网关 → ai-service → order-service）在三份日志里各是各的，
 * 时间对不齐时无法证明「这三条说的是同一件事」。
 * <p>
 * <b>为什么允许客户端传</b>：一次用户操作在前端可能触发多个请求（对话 + 轮询状态），
 * 客户端自己生成一个 id 才能把这些请求关联起来。代价是客户端可以伪造或复用别人的 id ——
 * 它只是一个<b>日志关联字段</b>，不参与任何鉴权与授权判定，伪造它不能让请求多拿到任何权限，
 * 只会让伪造者自己的轨迹更难查。
 * <p>
 * <b>为什么校验字符集而不是原样透传</b>：这个值会被写进每一行日志。允许换行符等于允许
 * 客户端往日志里插入伪造行（日志注入：构造 {@code "\n2026-01-01 ... [ERROR] 数据库连接失败"}
 * 就能凭空造出一条不存在的故障记录）。长度也要设限，否则一个 8KB 的头会放大到每一行日志里。
 * 不合法时<b>换发一个新的</b>而不是丢弃整条请求：标识只用于排查，为它拒绝请求是拿可用性
 * 换一个纯粹的可观测性问题。
 */
public final class RequestId {

    /** 请求头名。网关出站、服务间 Feign 调用、响应回写都用它 */
    public static final String HEADER = "X-Request-Id";

    /** 日志 MDC 键名，与 {@code logging.pattern.correlation} 里的 {@code %X{requestId}} 对应 */
    public static final String MDC_KEY = "requestId";

    /** 只收 URL 安全的标识字符；长度上限 64 与 UUID 同量级，够任何客户端生成方案使用 */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private RequestId() {
    }

    /**
     * 取一个可用的标识：客户端给的合法就用它，否则新发一个。
     * <p>
     * 长度上限是 64 而不是 128/256：这个值每行日志都要出现一次，
     * 而合法的生成方案（UUID、雪花号、时间戳+序号）都在 40 字符以内。
     */
    public static String resolve(String candidate) {
        return candidate != null && SAFE.matcher(candidate).matches() ? candidate : generate();
    }

    /**
     * 生成新标识：取 UUID 的前 16 位十六进制（64 位随机）。
     * <p>
     * <b>为什么截短</b>：它出现在每一行日志里，36 字符的完整 UUID 会把日志行挤长一半，
     * 而排查时人眼比对的只是前缀。<b>碰撞概率</b>：64 位随机下，100 万次请求的碰撞概率
     * 约 2.7×10⁻⁸；即使真的撞上，两条日志混在一起的代价也只是这次排查要换个线索，
     * 不会影响任何业务行为。
     */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 当前线程正在处理的请求标识；不在请求线程上（定时任务、启动期）时为 null */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /**
     * 把当前的日志上下文带进另一个线程执行。
     * <p>
     * MDC 是线程级的，而这套代码里好几处要把活儿交给别的线程：SSE 逐块推送、
     * 并行计划步骤里的工具调用。每一处漏掉一次，那一段日志就整段没有请求标识——
     * 而工具调用恰恰是最该串起来的一段：一次问答跨了三个服务，中间那一跳的日志却是散的，
     * 出站请求也因此不带这个头，下游只能自己发一个新的。
     * <p>
     * 执行完<b>恢复目标线程原来的上下文</b>而不是清空：池化线程在任务之间复用，
     * 清掉别人的上下文和留下自己的同样糟。
     */
    public static Runnable inherit(Runnable task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (captured == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(captured);
            }
            try {
                task.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
