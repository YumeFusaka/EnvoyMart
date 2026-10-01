package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.common.result.Result;

import java.util.function.Supplier;

/**
 * 工具调下游的唯一入口 —— 把「HTTP 200 + 业务码」这次调用的真实结局翻成工具能用的东西。
 * <p>
 * <b>为什么必须有个统一入口</b>：本项目的服务间应答一律是 HTTP 200 + 体里的 {@code code}
 * （见 {@code GlobalExceptionHandler}：参数错、状态冲突、非预期异常全都这么回）。
 * 于是工具里那句 {@code client.xxx(...).getData()} 有<b>三种完全不同的含义共用一个 null</b>：
 * 查到了、查不到、以及下游那边刚崩了一下。工具分不出来，就只能按最常见的那种解释——
 * 「没有找到」。实测下游搜索服务 5xx 时，商品检索工具回的是「没有找到相关商品」，
 * 模型转述给用户，用户以为商品下架了。<b>这不是文案问题，是工具在编事实。</b>
 * <p>
 * <b>为什么重试放在这里而不是 Feign 层</b>：{@code InternalFeignRetryConfig} 的判据是 HTTP 状态码，
 * 而这条路上根本没有非 200 的状态码——它看不见业务码，也就拦不住这一档。
 * 两层各管一段：Feign 管连接没建立、503、超时；这里管「连上了、下游说这次没做成」。
 * <p>
 * <b>为什么读和写要分成两个方法而不是一个</b>：同样是业务码 500，读可以重发（GET 幂等），
 * 写不可以——下游可能已经落库、只是应答没回来，重发就是第二次取消订单。
 * 这个判断只有调用点知道，所以由调用点选方法，而不是让本类去猜。
 * <p>
 * <b>本类只负责「这次调用成没成」，「没找到」的措辞仍归工具</b>：读接口遇到 4xx 返回
 * {@code null}，让工具说它自己那句「没有找到订单 X」——那是它对用户的口径，
 * 不该由这一层代写。
 */
@Slf4j
public final class Downstream {

    /** 读的重试窗口：两次重试，150ms、300ms 退避——最坏给单次工具调用加 450ms。 */
    private static final int MAX_RETRIES = 2;
    private static final long BASE_BACKOFF_MS = 150;

    private Downstream() {
    }

    /**
     * 一次<b>可重发</b>的下游调用（GET / 只读查询）。
     * <p>
     * 业务码 5xx 视为「下游这次没做成」并重试；重试仍失败就抛出，
     * 让工具如实报「暂时不可用」，而不是把 null 当成「没有」。
     * <p>
     * <b>重试窗口只有几百毫秒，救的是抖动不是宕机。</b>宕机该由熔断和降级管——
     * 工具层替用户在这里多等上几秒，换来的是每个提问都慢一截，而结果还是一样。
     */
    public static <T> T read(String service, Supplier<Result<T>> call) {
        Result<T> result = invoke(service, call);
        int attempts = 0;
        while (attempts < MAX_RETRIES && transientFailure(result)) {
            attempts++;
            // 两条日志各说一件事：这条证明重试真的发生了（否则「重试过了」只能靠结果反推——
            // 用户看到的「稍后再试」和从没重试过长得一模一样），下面那条证明它救回来了
            log.warn("[下游重试] {} 第 {} 次重试：上次业务码 {}", service, attempts, codeOf(result, -1));
            sleep(BASE_BACKOFF_MS << (attempts - 1));
            result = invoke(service, call);
        }
        if (attempts > 0 && codeOf(result, -1) == 200) {
            log.warn("[下游重试] {} 第 {} 次重试成功", service, attempts);
        }
        int code = codeOf(result, -1);
        if (code >= 500) {
            throw new DownstreamException(code, service + "暂时不可用（连续 " + (MAX_RETRIES + 1)
                    + " 次失败）。请告知用户稍后再试，不要据此说「没有」或「查不到」。", true);
        }
        if (code != 200) {
            // 4xx：参数错、无权限、查无此物——都是**结论**，重试无用。
            // 返回 null 让工具用它自己的措辞（「没有找到订单 X」），口径归工具
            log.warn("[下游] {} 返回业务码 {}：{}", service, code, msgOf(result));
            return null;
        }
        return result == null ? null : result.getData();
    }

    /**
     * 一次<b>不可重发</b>的下游调用（POST / 有副作用的操作）。
     * <p>
     * 任何非 200 的业务码都抛出：写操作没有「空结果」这一说，空的一定是没做成。
     * 原先这里返回 null，工具紧接着 {@code order.getOrderNo()} 就是一个
     * {@code NullPointerException}——模型拿到的是「Cannot invoke ... because "order" is null」，
     * 而下游其实早就把话说清楚了（「订单已取消，不能重复取消」）。
     */
    public static <T> T mutate(String service, Supplier<Result<T>> call) {
        Result<T> result = invoke(service, call);
        int code = codeOf(result, -1);
        if (code == 200) {
            return result.getData();
        }
        if (code >= 500) {
            // 与读不同，这里不重试：应答丢了不代表业务没生效。也说不出「没做成」——
            // 真相是**不知道**，所以文案要拦住模型自作主张地重来一次
            throw new DownstreamException(code, service + "暂时不可用，这次操作没有得到结果确认。"
                    + "请告知用户稍后再试，并先查询当前状态，不要直接重复操作。", true);
        }
        // 4xx 是下游给出的业务结论，原话就是要给用户看的那句
        throw new DownstreamException(code, msgOf(result) == null
                ? "操作未能完成，请稍后再试。" : msgOf(result), false);
    }

    /**
     * 工具 catch 块里的那一行 —— 异常 → 给模型看的一句话 + 该不该标成「暂时」。
     * <p>
     * <b>不回传未知异常的原始消息。</b>那是排障信息，里面带着内网地址、类名、栈里的一句话
     * （实测有过 {@code Connect to http://127.0.0.1:9200 failed}），
     * 而它会被原样送进模型上下文、再被模型转述给用户。详情进日志，用户拿人话。
     * <p>
     * 业务代码自己抛的 {@link IllegalArgumentException} / {@link IllegalStateException} 例外：
     * 那些消息是<b>写来给人看的</b>（「缺少用户身份」「缺少参数 items」），照传。
     */
    public static ToolResult failure(String what, Exception e) {
        return failure(what, e, null);
    }

    /**
     * 同上，外加一句「不要据此说 X」。
     * <p>
     * 工具失败时模型最常见的错误不是编造细节，而是<b>把失败读成否定</b>——
     * 检查没做成，它就告诉用户「没有发现相互作用」。这句话的危险在于它听着像个结论。
     * 所以每个工具的失败文案都要挡住自己那一句，而「挡哪一句」只有工具知道。
     * <p>
     * 本类自己抛的异常不用再补：{@link #read} / {@link #mutate} 的文案里已经写好了。
     */
    public static ToolResult failure(String what, Exception e, String doNotClaim) {
        if (e instanceof DownstreamException d) {
            log.warn("[工具失败] {} 业务码={} 瞬时={}：{}", what, d.getCode(), d.isTransient(), d.getMessage());
            return ToolResult.builder()
                    .success(false)
                    .errorMessage(d.getMessage())
                    .transientFailure(d.isTransient())
                    .build();
        }
        log.error("[工具失败] {}", what, e);
        // 业务异常的消息是写来给人看的（「缺少经过认证的用户身份」），照传；
        // 未知异常只给类型名——它的消息可能是「milvus timeout」这类内网细节
        String detail = e instanceof IllegalArgumentException || e instanceof IllegalStateException
                ? "：" + e.getMessage()
                : "（" + e.getClass().getSimpleName() + "）";
        return ToolResult.builder().success(false)
                .errorMessage(what + "未能完成" + detail + "，请稍后再试。"
                        + (doNotClaim == null ? "" : " 不要据此说「" + doNotClaim + "」。"))
                .build();
    }

    private static <T> Result<T> invoke(String service, Supplier<Result<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            // 走进来的不只是「连不上」：Feign 解不开应答体也抛在这里（DecodeException），
            // 而那一次调用其实连上了、对方也答了 200。所以这里必须打堆栈 ——
            // 真正的原因（哪个字段解不开、读超时还是连接被拒）全在 cause 链里，
            // 而下面那句给用户看的措辞只有类名。缺了这条日志，现场就只剩
            // 「商品服务连不上（DecodeException）」，排查的第一步（区分连不上与答非所问）
            // 就无从下手。实测踩到过：接口 200、应答是合法 JSON，工具却报「连不上」。
            log.warn("[下游失败] {} 调用异常（{}）", service, e.getClass().getSimpleName(), e);
            // 走到这里的是连接没建立、超时这类**传输层**失败——Feign 那边已经按安全判据重试过，
            // 到这里说明它也没辙了。包装成同一种异常，工具层不必认识两种失败
            throw new DownstreamException(-1, service + "连不上（" + e.getClass().getSimpleName()
                    + "）。请告知用户稍后再试，不要据此说「没有」或「查不到」。", true);
        }
    }

    /** 下游业务码 ≥500 = 它明确说「这次没做成」，与「查无此物」不是一回事 */
    private static <T> boolean transientFailure(Result<T> result) {
        return codeOf(result, -1) >= 500;
    }

    private static <T> int codeOf(Result<T> result, int fallback) {
        return result == null || result.getCode() == null ? fallback : result.getCode();
    }

    private static <T> String msgOf(Result<T> result) {
        return result == null ? null : result.getMsg();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamException(-1, "调用被中断，请稍后再试。", true);
        }
    }

    /** 下游明确说「这次没做成」时的信号，携带业务码与给模型看的那句话 */
    public static final class DownstreamException extends RuntimeException {

        private final int code;
        private final boolean transientFailure;

        DownstreamException(int code, String message, boolean transientFailure) {
            super(message);
            this.code = code;
            this.transientFailure = transientFailure;
        }

        public int getCode() {
            return code;
        }

        /** 过一会儿再试有意义。写操作的 5xx 也在这里——不是因为重发安全，是因为「结果未知」 */
        public boolean isTransient() {
            return transientFailure;
        }
    }
}
